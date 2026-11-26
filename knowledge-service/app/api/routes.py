"""HTTP 接口。Java 侧只通过这几个端点访问知识库。"""

from __future__ import annotations

from fastapi import APIRouter, Query
from pydantic import BaseModel

from app.ocr.ocr import Ocr
from app.retrieval.embedder import Embedder
from app.retrieval.reranker import get_reranker
from app.store.memory_store import store

router = APIRouter()


class Hit(BaseModel):
    id: str
    title: str
    snippet: str
    source: str
    score: float
    # 处方属性。None = 非药品或未标注。少这个字段不会报错——pydantic 默认丢弃未知字段，
    # 索引里有、接口返回时消失，排查起来只能靠端到端对比。新增字段务必同步这里。
    otc: bool | None = None
    # 交叉编码器给出的相关性分数。None 表示没走重排（精确命中捷径，或重排不可用）
    rerank_score: float | None = None
    # 药品结构化字段；非药品为 None
    drug: dict | None = None


class SearchResponse(BaseModel):
    query: str
    hits: list[Hit]


class GraphEdge(BaseModel):
    relation: str
    target: str


class GraphResponse(BaseModel):
    entity: str
    edges: list[GraphEdge]


@router.get("/health")
def health() -> dict:
    reranker = get_reranker()
    return {
        "status": "ok",
        "documents": store.size(),
        "search_backend": store.backend,
        "graph_backend": store.graph_backend,
        "vector": store.vector_enabled,
        "embedding_model": Embedder.get().model_name if store.vector_enabled else None,
        # 重排是可选增强：不可用时检索照常工作，只是精度降一档
        "rerank": reranker.available(),
        # loaded=false 表示模型还在预热，此时首批请求会慢（3-4s），可能触发上游超时
        "rerank_loaded": reranker.loaded,
        "rerank_model": reranker.model_name if reranker.available() else None,
        "ocr": Ocr.get().available(),
    }


class OcrRequest(BaseModel):
    image_base64: str


@router.post("/ocr")
def ocr(req: OcrRequest) -> dict:
    """图片转文字。只做 OCR，解析和解读走 Java 侧的 /reports。"""
    try:
        raw = Ocr.decode_base64(req.image_base64)
    except Exception as e:
        return {"ok": False, "text": "", "lines": 0, "error": f"图片解码失败：{e}"}

    text, lines = Ocr.get().extract(raw)
    return {"ok": bool(text), "text": text, "lines": lines}


@router.get("/search", response_model=SearchResponse)
def search(q: str = Query(..., min_length=1), top_k: int = Query(3, ge=1, le=10)) -> SearchResponse:
    hits = store.search(q, top_k)
    return SearchResponse(query=q, hits=[Hit(**h) for h in hits])


@router.get("/graph", response_model=GraphResponse)
def graph(entity: str = Query(..., min_length=1)) -> GraphResponse:
    edges = store.graph(entity)
    return GraphResponse(entity=entity, edges=[GraphEdge(**e) for e in edges])


@router.get("/drug")
def drug(name: str = Query(..., min_length=1)) -> dict:
    """按药名查结构化说明书字段。

    单独一个端点而不是复用 /search：药品需要的是字段，不是"最相关的几段文字"。
    让 drug_info 工具去检索再自己解析文本，等于把结构化的工作推给模型。
    """
    hits = store.search(name, 3)
    for h in hits:
        d = h.get("drug")
        if d and (name in h["title"] or d["name"] in name or name in d["name"]):
            return {"found": True, "title": h["title"], "source": h.get("source", ""), **d}
    # 退一步：只要检索到带结构化字段的药品，也返回（药名可能有剂型后缀）
    for h in hits:
        d = h.get("drug")
        if d:
            return {"found": True, "title": h["title"], "source": h.get("source", ""), **d}
    return {"found": False, "query": name}


@router.get("/entities")
def entities() -> dict:
    return {"entities": store.entities()}
