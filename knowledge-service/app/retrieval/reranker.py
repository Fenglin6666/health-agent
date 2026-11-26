"""重排（rerank）。

混合检索的前两段（BM25 + kNN）都是"各自打分再融合"，它们判断的是"这个词/语义像不像"，
不是"这段文字到底能不能回答问题"。重排模型是交叉编码器：把 query 和文档拼在一起过一遍，
直接输出相关性分数——精度高得多，代价是慢，所以只能对少量候选做。

放在 RRF 之后、截断之前：
    BM25 + kNN → RRF 融合出 ~10 条候选 → cross-encoder 重排 → 取 top_k

模型选 `BAAI/bge-reranker-base`：中英文都支持，ONNX 量化后约 300MB，
CPU 上 10 条候选约 100-300ms。比它更强的 v2-m3 是 2GB+，个人机器上不划算。

失败处理：模型下载失败或推理异常时**静默降级为不重排**，并在日志里说一次。
检索不能因为重排不可用就整个失败——重排是精度优化，不是功能依赖。
"""

from __future__ import annotations

import logging
import os
import time

log = logging.getLogger(__name__)

DEFAULT_MODEL = "BAAI/bge-reranker-base"
# 连续失败后冷却，避免每次查询都卡在加载/超时上
FAIL_COOLDOWN_SECONDS = 300.0

#: 全体候选都低于这个分数时，判定"知识库里没有能回答这个问题的东西"，返回空结果。
#:
#: 为什么需要这道闸：kNN 永远返回 top-k，它没有"没有"这个概念。用户问"帮我写一段
#: python 代码"，向量检索照样会挑出三味药，而这三味药会被当成检索证据喂给模型——
#: 给出错误出处比没有出处更糟。向量相似度本身分不开（实测"睡不着" 0.6228 vs
#: "推荐个显卡" 0.6164，差 0.006），但交叉编码器分得开。
#:
#: -5 这个数来自实测（45 条文档，bge-reranker-base 原始分）：
#:   无关问句最高 -6.14（今天天气怎么样），最低 -10.19（帮我写一段python代码）；
#:   真实问句最低 -1.17（华法林和布洛芬能一起吃吗）。
#: 注意门槛**不能设在 0**：重排模型对"头疼"只给 -0.05、"华法林和布洛芬"只给 -1.17，
#: 都是正确答案却是负分。0 会把正确答案一起毙掉。
DROP_BELOW = float(os.getenv("RERANK_DROP_BELOW", "-5"))


class Reranker:
    def __init__(self, model_name: str = DEFAULT_MODEL, enabled: bool = True,
                 threshold: float = 0.0) -> None:
        self.model_name = model_name
        self.enabled = enabled
        # 只提升高于这个分数的文档。0 是交叉编码器的"相关/不相关"天然分界
        self.threshold = threshold
        self._model = None
        self._failed_until = 0.0
        self._warned = False

    def available(self) -> bool:
        return self.enabled and time.time() >= self._failed_until

    @property
    def loaded(self) -> bool:
        """模型是否真的加载进内存了。区别于 available（只是"没被熔断"）。"""
        return self._model is not None

    def ensure(self) -> bool:
        """公开的加载入口，供启动预热调用。"""
        return self._ensure()

    def _ensure(self) -> bool:
        if self._model is not None:
            return True
        if not self.available():
            return False
        try:
            from fastembed.rerank.cross_encoder import TextCrossEncoder

            # HF_ENDPOINT 已经在 app/__init__.py 里设好——这里再设没用，
            # huggingface_hub 在 import 时就把地址读成常量了。
            self._model = TextCrossEncoder(model_name=self.model_name)
            log.info("rerank 模型就绪：%s", self.model_name)
            return True
        except Exception as e:  # noqa: BLE001 - 模型加载失败原因很多，统一降级
            self._failed_until = time.time() + FAIL_COOLDOWN_SECONDS
            if not self._warned:
                log.warning("rerank 不可用，降级为不重排：%s", e)
                self._warned = True
            return False

    def rerank(self, query: str, hits: list[dict], top_k: int) -> list[dict]:
        """对候选重排。任何异常都返回原顺序，调用方不用处理失败。

        <p>策略不是"全量按分数排序"，而是<b>只提升明确相关的</b>：

        1. rerank 分数 > 阈值（默认 0）的文档，按分数降序排到前面；
        2. 其余保持 RRF 融合顺序。

        为什么这么做，是实测逼出来的。bge-reranker-base 对"问题—答案"型文本判断很准
        （"血脂高怎么办" vs "高血脂患者的饮食与运动建议" 给 +1.51，vs 无关文档给 -8），
        但它对**一堆都不相关**的候选给出的负分排序接近噪声——实测出现过
        "奥美拉唑 -0.94 排在 血脂四项解读 -1.21 前面"，直接把正确答案挤出前三。

        结论：模型有能力判断"相关/不相关"，但没能力在没有相关文档时给出有意义的相对顺序。
        那就只用它的强项——**当裁判，不当排序器**。
        """
        if len(hits) <= 1 or not self._ensure():
            return hits[:top_k]

        # 只取标题 + 前 120 字：交叉编码器的判断力集中在前半段，
        # 全文喂进去只会让 CPU 推理时间线性上涨
        docs = [f"{h.get('title', '')}。{h.get('snippet', '')[:120]}" for h in hits]
        try:
            scores = list(self._model.rerank(query, docs))
        except Exception as e:  # noqa: BLE001
            self._failed_until = time.time() + FAIL_COOLDOWN_SECONDS
            log.warning("rerank 推理失败，本次降级：%s", e)
            return hits[:top_k]

        if len(scores) != len(hits):
            return hits[:top_k]

        promoted: list[tuple[float, dict]] = []
        kept: list[dict] = []
        for score, hit in zip(scores, hits):
            s = float(score)
            if s > self.threshold:
                promoted.append((s, {**hit, "rerank_score": round(s, 4)}))
            else:
                # 负分保留原顺序，但把分数带出去，方便排查"为什么没被提上来"
                kept.append({**hit, "rerank_score": round(s, 4)})

        promoted.sort(key=lambda pair: -pair[0])
        ordered = [hit for _, hit in promoted] + kept
        return ordered[:top_k]


_instance: Reranker | None = None


def get_reranker() -> Reranker:
    """全局单例。模型只加载一次，进程内复用。"""
    global _instance
    if _instance is None:
        enabled = os.getenv("RERANK_ENABLED", "true").lower() not in ("0", "false", "no")
        model = os.getenv("RERANK_MODEL", DEFAULT_MODEL)
        threshold = float(os.getenv("RERANK_THRESHOLD", "0"))
        _instance = Reranker(model_name=model, enabled=enabled, threshold=threshold)
    return _instance
