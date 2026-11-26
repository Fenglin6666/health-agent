from __future__ import annotations

import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import router
from app.retrieval.reranker import get_reranker

log = logging.getLogger(__name__)


def _warmup() -> None:
    """后台预热重排模型。

    不做这件事的后果实测过：服务重启后第一个检索请求要等模型加载（3-4 秒），
    而 Java 侧的检索超时是 6 秒——于是第一个用户得到的是"我没有查到可靠资料"，
    数据明明在库里。**冷启动的代价不能由第一个用户付。**

    放后台线程：加载失败也不该拖住启动，重排本来就允许降级。
    """
    try:
        reranker = get_reranker()
        if reranker.ensure():
            log.info("rerank 预热完成：%s", reranker.model_name)
        else:
            log.warning("rerank 预热失败，检索将不重排")
    except Exception as e:  # noqa: BLE001
        log.warning("rerank 预热异常：%s", e)


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=_warmup, name="rerank-warmup", daemon=True).start()
    yield


app = FastAPI(
    title="Health Knowledge Service",
    version="0.1.0",
    description="知识检索与图谱查询。Java 主服务通过 HTTP 调用。",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:8080"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8001, reload=True)
