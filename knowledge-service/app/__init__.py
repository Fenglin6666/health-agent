"""知识服务：检索 + 图谱查询 + 文档解析。

这里设置 HF 镜像地址是**刻意放在包初始化**里的：`huggingface_hub` 在 import 时就把
`HF_ENDPOINT` 读进常量，之后再改 `os.environ` 不生效。写在 reranker 里看着更内聚，
但实际无效——模型会去连 huggingface.co，然后超时失败（实测：等 70 秒后报
"Could not load model ... from any source"）。

同理 `HF_HUB_DISABLE_SYMLINKS_WARNING`：Windows 不支持符号链接，每条下载都刷警告。
"""

import os

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

__version__ = "0.1.0"
