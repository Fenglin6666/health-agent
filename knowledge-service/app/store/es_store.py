"""Elasticsearch 存储层：BM25 + kNN 混合检索。

用标准库 urllib 直连 REST，不引 elasticsearch-py——后者依赖重，而我们只用四个 API：
建索引、bulk 导入、search、count。

中文分词用 ES 内置的 `cjk_bigram` filter + `standard` tokenizer，
不需要装 ik 插件（装插件要重建镜像，成本高于收益）。

混合策略是 RRF（Reciprocal Rank Fusion）：BM25 和 kNN 各出一份排名，
按 `1/(k+rank)` 融合。不做分数加权，因为两边的分数量纲完全不同。
"""

from __future__ import annotations

import hashlib
import json
import logging
import urllib.error
import urllib.request
from typing import Any

from app.retrieval.embedder import Embedder
from app.retrieval.reranker import DROP_BELOW, get_reranker

log = logging.getLogger(__name__)

INDEX = "ha_knowledge_v2"
RRF_K = 60


def _fingerprint(docs: list[dict]) -> str:
    """种子数据指纹。用来判断"数据变了但条数没变"——只看条数会漏掉改内容的情况。

    <p>哈希的是标题和正文<b>原文</b>，不是长度。长度是个偷懒的代理指标，它有两处漏：
    改了标题但正文没动（指纹不变）、正文等长改写（指纹也不变）。两种都会让索引停在
    旧数据上而且不报错，排查时只能人肉对比。45 条文档算一次 MD5 是微秒级，不值得省。
    """
    raw = "|".join(
        f"{d['id']}:{d.get('title', '')}:{d['content']}:{d.get('otc')}"
        for d in sorted(docs, key=lambda x: x["id"])
    )
    return hashlib.md5(raw.encode("utf-8")).hexdigest()[:12]


def _mapping(dims: int | None, fingerprint: str = "") -> dict[str, Any]:
    properties: dict[str, Any] = {
        "title": {"type": "text", "analyzer": "cjk"},
        "content": {"type": "text", "analyzer": "cjk"},
        "source": {"type": "keyword"},
        "tags": {"type": "keyword"},
        # 处方属性进索引：检索结果直接带出来，模型不用凭记忆判断
        "otc": {"type": "boolean"},
        # 药品结构化字段。enabled=false 表示只存不索引——它只用于返回，不参与检索，
        # 建了倒排索引纯属浪费写入开销。
        "drug": {"type": "object", "enabled": False},
    }
    # 没有向量化能力时干脆不建向量字段。
    # 之前是塞一个 dims=1 的占位——那是假的，任何一次写入都会因为维度不匹配而失败，
    # 而且看 mapping 的人会以为这里有向量。
    if dims:
        properties["vector"] = {
            "type": "dense_vector",
            "dims": dims,
            "index": True,
            "similarity": "cosine",
        }

    return {
        "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "analysis": {
                "analyzer": {
                    "cjk": {
                        "type": "custom",
                        "tokenizer": "standard",
                        "filter": ["cjk_width", "lowercase", "cjk_bigram"],
                    }
                }
            },
        },
        "mappings": {
            "_meta": {"fingerprint": fingerprint},
            "properties": properties,
        },
    }


class EsStore:
    def __init__(self, base_url: str, timeout: float = 2.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.embedder = Embedder.get()
        self.vector_enabled = False

    # ---------- 基础 ----------

    def _request(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        url = f"{self.base_url}/{path.lstrip('/')}"
        data = None
        headers = {"Content-Type": "application/json"}
        if body is not None:
            if isinstance(body, str):
                data = body.encode("utf-8")
                headers["Content-Type"] = "application/x-ndjson"
            else:
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
                return resp.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            return e.code, {"error": e.read().decode("utf-8", "ignore")[:300]}
        except Exception as e:
            return 0, {"error": str(e)}

    def available(self) -> bool:
        status, _ = self._request("GET", "/")
        return status == 200

    # ---------- 索引 ----------

    def ensure_index(self, docs: list[dict], auto_rebuild: bool = True) -> bool:
        """确保索引存在且与种子数据一致。

        种子数据变了（数量不同）就重建——本地开发时改完数据不用手动删索引。
        生产环境把 auto_rebuild 关掉，改数据走正式的 reindex 流程。
        """
        self.vector_enabled = self.embedder.available()
        # 维度只来自唯一的那个 embedding 实现；没有向量能力时就是 None，索引里不建向量字段
        dims = self.embedder.dim if self.vector_enabled else None
        fp = _fingerprint(docs)

        status, _ = self._request("HEAD", f"/{INDEX}")
        if status == 200:
            # 文档数、数据指纹、向量维度三项都要对得上。
            # 只看条数会漏掉"改了一条内容但总数没变"，那种情况索引里永远是旧数据，
            # 而且不报错——排查起来只能靠人肉对比，非常贵。
            same = self.count() == len(docs) and self._fingerprint_match(fp)
            if same and dims:
                same = self._dims_match(dims)
            if not auto_rebuild or same:
                return True
            log.info("索引数据或维度变了，重建 %s（dims=%s, fp=%s）", INDEX, dims, fp)
            self._request("DELETE", f"/{INDEX}")

        status, _ = self._request("PUT", f"/{INDEX}", _mapping(dims, fp))
        if status not in (200, 201):
            return False

        return self.bulk_index(docs)

    def _fingerprint_match(self, fp: str) -> bool:
        status, body = self._request("GET", f"/{INDEX}/_mapping")
        if status != 200:
            return False
        try:
            return body[INDEX]["mappings"].get("_meta", {}).get("fingerprint") == fp
        except (KeyError, TypeError):
            return False

    def _dims_match(self, dims: int) -> bool:
        status, body = self._request("GET", f"/{INDEX}/_mapping")
        if status != 200:
            return False
        try:
            props = body[INDEX]["mappings"]["properties"]
            return props.get("vector", {}).get("dims") == dims
        except (KeyError, TypeError):
            return False

    def bulk_index(self, docs: list[dict]) -> bool:
        vectors: list[list[float]] = []
        if self.vector_enabled:
            vectors = self.embedder.embed_batch(
                [f"{d['title']} {d['content']}" for d in docs])

        lines: list[str] = []
        for i, doc in enumerate(docs):
            lines.append(json.dumps({"index": {"_index": INDEX, "_id": doc["id"]}}))
            payload = {k: v for k, v in doc.items() if k != "id"}
            if vectors and i < len(vectors) and vectors[i]:
                payload["vector"] = vectors[i]
            lines.append(json.dumps(payload, ensure_ascii=False))
        if not lines:
            return True

        status, body = self._request("POST", "/_bulk?refresh=true", "\n".join(lines) + "\n")
        if status not in (200, 201):
            return False
        return not body.get("errors", False)

    # ---------- 检索 ----------

    def search(self, query: str, top_k: int = 3) -> list[dict]:
        # 6 条而不是 10 条：重排成本与候选数线性相关，而 RRF 前 6 已经覆盖绝大多数正确答案。
        # 实测 10 条时单次检索 3.9s（超过上游 3s 超时），6 条降到 2s 以内。
        candidates = max(top_k * 2, 6)

        # 先只跑 BM25。命中够好就不算向量——远程 embedding 一次要 300-800ms，
        # 而"布洛芬缓释胶囊"这种药名查询，字面匹配已经足够，算向量纯属浪费。
        # 这条捷径把精确药名查询从 ~700ms 拉回 ~30ms，也让检索延迟变得可预测。
        bm25 = self._bm25(query, candidates)
        if self._is_confident(query, bm25):
            return bm25[:top_k]

        knn: list[dict] = []
        if self.vector_enabled:
            vec = self.embedder.embed(query)
            if vec:
                knn = self._knn(vec, candidates)

        if not bm25 and not knn:
            return []
        if not knn:
            fused = bm25[:candidates]
        elif not bm25:
            fused = knn[:candidates]
        else:
            fused = self._rrf([bm25, knn], candidates)

        # 重排：融合只是"排名相加"，不判断文档到底能不能回答。交叉编码器能判断，
        # 但慢，所以只对融合后的少量候选做，且失败时静默退回融合顺序。
        ranked = get_reranker().rerank(query, fused, top_k)
        return self._drop_hopeless(ranked)

    @staticmethod
    def _drop_hopeless(ranked: list[dict]) -> list[dict]:
        """逐条剔除重排分低于 DROP_BELOW 的候选。

        <p><b>为什么逐条剔，而不是"整体都低才清空"</b>：实测过。查询"头疼"时，
        向量检索把氯苯那敏排到了第一（"感冒"和"头疼"语义相近），而重排给它 -5.26、
        给布洛芬 -0.05——重排明确知道它不相关。但重排的策略是"只提升正分候选，
        其余保持 RRF 顺序"，于是 -5.26 稳稳压着 -0.05。

        <p>按条剔除就解决了：-5.26 被扔掉，布洛芬自然回到第一。同时保留了
        "不按负分排序"这条原则——-0.05 和 -0.42 之间谁先谁后仍然看 RRF，
        因为那个区间里重排的相对顺序确实是噪声。

        <p>没跑重排（模型不可用）时没有分数，无法判断，原样返回。
        """
        kept = [h for h in ranked if h.get("rerank_score") is None or h["rerank_score"] >= DROP_BELOW]
        return kept

    @staticmethod
    def _is_confident(query: str, hits: list[dict]) -> bool:
        """标题基本命中就算够好。宁严勿宽——放宽会把"红细胞压积→阿莫西林"那类错误放回来。"""
        if not hits:
            return False
        title = hits[0].get("title", "")
        q = query.strip()
        if not q:
            return False
        # 药名/指标名通常整串出现在标题里（"布洛芬缓释胶囊（示例）"）
        if q in title:
            return True
        # 标题主干出现在查询里（查询带症状描述时）
        stem = title.split("（")[0]
        return len(stem) >= 2 and stem in q

    def _bm25(self, query: str, size: int) -> list[dict]:
        payload = {
            "size": size,
            "query": {
                "multi_match": {
                    "query": query,
                    "fields": ["title^2", "content"],
                    "type": "best_fields",
                    # 用**绝对个数**而不是百分比。原因实测过：
                    # cjk 分析器把中文切成二元组，"华法林和布洛芬能一起吃吗" 会产生 11 个 term，
                    # 而华法林那条文档最多命中 4 个（华法/法林/布洛/洛芬）——占比 36%，
                    # 任何百分比阈值都会把它滤掉，于是"两种药能不能一起吃"这个最该答对的问题
                    # 直接空召回。百分比随查询长度水涨船高，长问句天然吃亏。
                    #
                    # "1<2"：只有 1 个 term 时要求全中（"布洛芬"），多个 term 时要求命中 2 个。
                    # 门槛停在 2 而不是 1：1 会让「红细胞压积偏高」把「阿莫西林」带出来
                    # （两者共享的只有"细胞"这种泛词），2 就没有这个噪声。
                    "minimum_should_match": "1<2",
                }
            },
            "highlight": {"fields": {"content": {"fragment_size": 120, "number_of_fragments": 1}}},
        }
        status, body = self._request("POST", f"/{INDEX}/_search", payload)
        if status != 200:
            return []
        return [self._to_hit(h) for h in body.get("hits", {}).get("hits", [])]

    def _knn(self, vector: list[float], size: int) -> list[dict]:
        payload = {
            "size": size,
            "knn": {
                "field": "vector",
                "query_vector": vector,
                "k": size,
                "num_candidates": max(size * 5, 50),
            },
        }
        status, body = self._request("POST", f"/{INDEX}/_search", payload)
        if status != 200:
            return []
        return [self._to_hit(h) for h in body.get("hits", {}).get("hits", [])]

    @staticmethod
    def _to_hit(h: dict) -> dict:
        src = h.get("_source", {})
        snippet = src.get("content", "")
        hl = h.get("highlight", {}).get("content")
        if hl:
            snippet = hl[0].replace("<em>", "").replace("</em>", "")
        return {
            "id": h.get("_id", ""),
            "title": src.get("title", ""),
            "snippet": snippet[:240],
            "source": src.get("source", ""),
            # None 表示非药品或未标注；上层据此决定"能不能推荐"
            "otc": src.get("otc"),
            "drug": src.get("drug"),
            "score": round(float(h.get("_score") or 0), 4),
        }

    @staticmethod
    def _rrf(rankings: list[list[dict]], top_k: int) -> list[dict]:
        scores: dict[str, float] = {}
        docs: dict[str, dict] = {}
        for ranking in rankings:
            for rank, hit in enumerate(ranking):
                doc_id = hit["id"]
                docs[doc_id] = hit
                scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (RRF_K + rank + 1)
        ordered = sorted(scores.items(), key=lambda kv: -kv[1])[:top_k]
        return [{**docs[i], "score": round(s, 6)} for i, s in ordered]

    def count(self) -> int:
        status, body = self._request("GET", f"/{INDEX}/_count")
        return body.get("count", 0) if status == 200 else 0
