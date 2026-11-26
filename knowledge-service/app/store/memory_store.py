"""知识存储。

优先用 Elasticsearch；ES 不可用时自动回退到内存 BM25。
两条路径返回同样的结构，上层无感——这是"降级不降能力"的落地方式。
"""

from __future__ import annotations

import logging
import os
import time

from app.retrieval.bm25 import BM25Index
from app.store.es_store import EsStore
from app.store.neo4j_store import Neo4jStore
from app.store.seed_data import SEED_DOCS, SEED_GRAPH

log = logging.getLogger(__name__)

# 启动探测的重试次数。ES/Neo4j 是共享容器，启动瞬间可能正忙（GC、建索引、别的项目在压），
# 一次探测失败就永久降级到内存，代价太大——数据看着"少了"，而且不会自愈。
PROBE_ATTEMPTS = 5
PROBE_DELAY_SECONDS = 1.5


def _probe(check, label: str) -> bool:
    for i in range(PROBE_ATTEMPTS):
        if check():
            return True
        if i < PROBE_ATTEMPTS - 1:
            log.info("%s 暂不可用，%.1fs 后重试（%d/%d）", label, PROBE_DELAY_SECONDS, i + 1, PROBE_ATTEMPTS)
            time.sleep(PROBE_DELAY_SECONDS)
    return False


class KnowledgeStore:
    def __init__(self) -> None:
        self._index = BM25Index()
        self._index.add(SEED_DOCS)
        self._graph = SEED_GRAPH

        self._es: EsStore | None = None
        self._neo4j: Neo4jStore | None = None
        self._init_es()
        self._init_neo4j()

    def _init_es(self) -> None:
        url = os.getenv("ES_URL", "http://localhost:9200")
        es = EsStore(url, timeout=float(os.getenv("ES_TIMEOUT", "2")))
        if not _probe(es.available, "Elasticsearch"):
            log.warning("ES 不可用（%s），回退到内存 BM25", url)
            return

        docs = [
            {"id": d.id, "title": d.title, "content": d.content,
             "source": d.source, "tags": d.tags, "otc": d.otc, "drug": d.drug}
            for d in SEED_DOCS
        ]
        if es.ensure_index(docs):
            self._es = es
            log.info("ES 就绪：%s，文档数 %d", url, es.count())
        else:
            log.warning("ES 索引初始化失败，回退到内存 BM25")

    @property
    def backend(self) -> str:
        return "elasticsearch" if self._es else "memory-bm25"

    def _init_neo4j(self) -> None:
        url = os.getenv("NEO4J_URL", "http://localhost:7474")
        neo = Neo4jStore(
            url,
            os.getenv("NEO4J_USER", "neo4j"),
            os.getenv("NEO4J_PASSWORD", "ha_local_dev"),
            timeout=float(os.getenv("NEO4J_TIMEOUT", "3")),
        )
        if not _probe(neo.available, "Neo4j"):
            log.warning("Neo4j 不可用（%s），图谱查询回退到内存字典", url)
            return
        if neo.init_graph(SEED_GRAPH):
            self._neo4j = neo
            log.info("Neo4j 就绪：%s，实体数 %d", url, neo.count())
        else:
            log.warning("Neo4j 图谱初始化失败，回退到内存字典")

    @property
    def graph_backend(self) -> str:
        return "neo4j" if self._neo4j else "memory-dict"

    @property
    def vector_enabled(self) -> bool:
        return bool(self._es and self._es.vector_enabled)

    def search(self, query: str, top_k: int = 3) -> list[dict]:
        if self._es:
            # **不回退内存**。ES 返回空是"知识库里没有能回答这个问题的东西"这个结论，
            # 不是故障——重排闸（DROP_BELOW）就是这么判的。
            #
            # 原来这里有一条"ES 空则回退内存 BM25"的分支，理由是"cjk 对超短查询可能
            # 召回不到"。实测这是个坑：垃圾问题被 ES 的闸正确清空后，回退把不相关的
            # 又捞了回来（"帮我写一段python代码"返回 6 味药），而且内存 BM25 没有闸，
            # 分数还更高。**更弱的实现不可能给出更好的召回**，只会绕过前面所有的判断。
            return self._es.search(query, top_k)
        return self._memory_search(query, top_k)

    def _memory_search(self, query: str, top_k: int) -> list[dict]:
        hits = self._index.search(query, top_k)
        return [
            {
                "id": doc.id,
                "title": doc.title,
                "snippet": doc.content[:240],
                "source": doc.source,
                "otc": doc.otc,
                "drug": doc.drug,
                "score": round(score, 4),
            }
            for doc, score in hits
        ]

    def graph(self, entity: str) -> list[dict]:
        if self._neo4j:
            rows = self._neo4j.query(entity)
            if rows:
                return rows
        return self._graph.get(entity, [])

    def entities(self) -> list[str]:
        if self._neo4j:
            rows = self._neo4j.entities()
            if rows:
                return rows
        return sorted(self._graph.keys())

    def size(self) -> int:
        return self._es.count() if self._es else len(self._index)


store = KnowledgeStore()
