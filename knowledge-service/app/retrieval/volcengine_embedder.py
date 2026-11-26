"""火山方舟多模态 embedding。全系统唯一的向量化实现。

模型 `doubao-embedding-vision-251215`，2048 维，支持文本和图片。

为什么只留这一个（不再有本地 512 维实现）：向量和模型强绑定，两套向量等于两套索引、
两份知识库。详见 `embedder.py` 的说明。

代价要认：要联网，单次 200-500ms。这是它唯一的缺点，靠"精确命中不算向量"和
查询缓存来抵消。

API 特性（实测）：
- `input` 虽然是数组，但**一次只处理一条**，多元素会合并成一个向量
- 响应是 `data.embedding`（对象），不是 OpenAI 那种 `data[0].embedding`（数组）
"""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.request
from collections import OrderedDict
from typing import Optional

log = logging.getLogger(__name__)

DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
DEFAULT_MODEL = "doubao-embedding-vision-251215"
DIM = 2048

# 查询侧缓存。同一个药名/症状在一次会话里会反复检索，命中缓存能把 300-800ms 降到微秒级。
CACHE_SIZE = 256
# 失败熔断时长：网络抖动后 60 秒内不再重试，避免每个请求都卡满超时。
FAIL_COOLDOWN_SECONDS = 60.0


class VolcengineEmbedder:
    dim = DIM

    def __init__(self, api_key: str, model: str = DEFAULT_MODEL,
                 base_url: str = DEFAULT_BASE_URL, timeout: float = 10.0) -> None:
        self.api_key = api_key
        self.model = model
        self.model_name = model
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._failed_until = 0.0
        self._cache: OrderedDict[str, list[float]] = OrderedDict()

    def available(self) -> bool:
        return bool(self.api_key) and time.time() >= self._failed_until

    def embed(self, text: str) -> list[float]:
        if not text or not self.available():
            return []
        cached = self._cache.get(text)
        if cached is not None:
            self._cache.move_to_end(text)
            return cached

        vectors = self._call([{"type": "text", "text": text}])
        vec = vectors[0] if vectors else []
        if vec:
            self._cache[text] = vec
            if len(self._cache) > CACHE_SIZE:
                self._cache.popitem(last=False)
        return vec

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """API 一次只出一条，这里循环。索引阶段可接受（11 条约 5 秒）。"""
        out: list[list[float]] = []
        for t in texts:
            out.append(self.embed(t))
        return out

    def embed_image(self, image_url: str) -> list[float]:
        """图片 embedding。目前没用到，接口先留着。"""
        vectors = self._call([{"type": "image_url", "image_url": {"url": image_url}}])
        return vectors[0] if vectors else []

    def _call(self, inputs: list[dict]) -> list[list[float]]:
        payload = json.dumps({"model": self.model, "input": inputs}).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/embeddings/multimodal",
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:200]
            log.warning("volcengine embedding http %s: %s", e.code, detail)
            # 4xx 是配置问题（key 错、模型名错），熔断掉，别每轮都打一遍
            if 400 <= e.code < 500:
                self._failed_until = time.time() + FAIL_COOLDOWN_SECONDS
            return []
        except Exception as e:
            log.warning("volcengine embedding failed: %s", e)
            # 网络类故障给一个冷却窗口，而不是永久关闭——否则一次抖动会让检索
            # 整个降级成 BM25，直到进程重启，而没人会去重启它
            self._failed_until = time.time() + FAIL_COOLDOWN_SECONDS
            return []

        data = body.get("data")
        if isinstance(data, dict):
            emb = data.get("embedding")
            return [emb] if emb else []
        if isinstance(data, list):
            return [d.get("embedding", []) for d in data if d.get("embedding")]
        return []
