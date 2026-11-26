"""文本向量化：只保留一种实现。

**为什么不做"本地 + 云端"双实现**：向量和模型是强绑定的。bge-small-zh 的 512 维向量
和 doubao 的 2048 维向量不只是维度不同，语义空间也完全不同——两者不能混在同一个索引里
检索，甚至不能互相比较。要同时支持，就得维护两份知识库、两套索引、两条评测基线。

而"换一个 embedding 就重建索引"这件事本身是对的（维度不匹配的报错点离原因很远），
但它掩盖了一个更深的问题：**只要有两套向量，就永远有"当前用的是哪套"这个隐性状态**，
而这个状态会渗进缓存、评测结果和线上数据里。收敛到一套，这类问题整体消失。

所以只保留火山方舟 `doubao-embedding-vision`（2048 维，支持图片）。

**没配 key 时退回"无向量"模式**：检索降级为 BM25 + 重排，功能仍在，只是召回差一档，
`/health` 显示 `vector: false`。这是可接受的降级——不像数据持久化，这里不丢任何东西。
"""

from __future__ import annotations

import logging
import os
import threading

log = logging.getLogger(__name__)


class NoopEmbedder:
    """没有 key 时的占位实现。

    存在的意义是让调用方不用到处判空：`available()` 返回 False、`embed()` 返回空列表，
    上层自然走"只用 BM25"的分支。
    """

    dim = 0
    model_name = None

    def available(self) -> bool:
        return False

    def embed(self, text: str) -> list[float]:
        return []

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        return []

    def embed_image(self, image_url: str) -> list[float]:
        return []


class Embedder:
    """统一入口。调用方只依赖这个类。"""

    _instance = None
    _lock = threading.Lock()

    @classmethod
    def get(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls._create()
        return cls._instance

    @classmethod
    def _create(cls):
        key = os.getenv("VOLC_ARK_API_KEY", "").strip()
        if not key:
            log.warning("没配 VOLC_ARK_API_KEY，向量检索关闭（降级为 BM25 + 重排）")
            return NoopEmbedder()

        from app.retrieval.volcengine_embedder import DEFAULT_MODEL, VolcengineEmbedder

        model = os.getenv("VOLC_EMBED_MODEL", DEFAULT_MODEL)
        log.info("embedding = volcengine %s (%d dims)", model, VolcengineEmbedder.dim)
        return VolcengineEmbedder(key, model)

    @classmethod
    def reset(cls) -> None:
        """仅供测试：清掉单例，让下次调用重新读配置。"""
        with cls._lock:
            cls._instance = None
