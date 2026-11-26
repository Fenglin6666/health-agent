"""图片 OCR。

用 RapidOCR（onnxruntime 版 PaddleOCR），纯本地、无外部 API、中文识别可用。
懒加载：第一次调用会初始化引擎（约 2 秒），之后复用。

只做"图转字"，不碰业务——转出来的文本走和手动粘贴完全相同的解析链路。
"""

from __future__ import annotations

import base64
import logging
import threading
from typing import Optional

log = logging.getLogger(__name__)


class Ocr:
    _instance: Optional["Ocr"] = None
    _lock = threading.Lock()

    def __init__(self) -> None:
        self._engine = None
        self._failed = False

    @classmethod
    def get(cls) -> "Ocr":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance

    def _ensure(self) -> bool:
        if self._engine is not None:
            return True
        if self._failed:
            return False
        try:
            from rapidocr_onnxruntime import RapidOCR

            self._engine = RapidOCR()
            log.info("OCR engine loaded")
            return True
        except Exception as e:
            log.warning("OCR engine unavailable: %s", e)
            self._failed = True
            return False

    def available(self) -> bool:
        return self._ensure()

    def extract(self, image_bytes: bytes) -> tuple[str, int]:
        """返回 (文本, 识别到的行数)。失败返回 ("", 0)。"""
        if not image_bytes or not self._ensure():
            return "", 0
        try:
            result, _ = self._engine(image_bytes)
            if not result:
                return "", 0
            lines = self._merge_lines(result)
            return "\n".join(lines), len(lines)
        except Exception as e:
            log.warning("OCR failed: %s", e)
            return "", 0

    @staticmethod
    def _merge_lines(result: list) -> list[str]:
        """
        按 y 坐标把文本块合并成行。

        RapidOCR 是按文本框返回的，同一行里的"血红蛋白 / 102 / g/L / 115-150"
        会变成四个块。不合并的话，下游拿到的是一堆碎片，指标和数值对不上。
        """
        items = []
        for item in result:
            if len(item) < 2:
                continue
            box, text = item[0], item[1]
            if not text:
                continue
            try:
                ys = [float(p[1]) for p in box]
                xs = [float(p[0]) for p in box]
            except (TypeError, IndexError):
                continue
            items.append((sum(ys) / len(ys), sum(xs) / len(xs), text))

        if not items:
            return []

        items.sort(key=lambda it: (it[0], it[1]))

        rows: list[list[tuple[float, str]]] = []
        current: list[tuple[float, str]] = []
        last_y = None
        for y, x, text in items:
            # 行高阈值：同一行的块 y 差通常小于半个字高
            if last_y is None or abs(y - last_y) < 15:
                current.append((x, text))
            else:
                rows.append(current)
                current = [(x, text)]
            last_y = y
        if current:
            rows.append(current)

        return ["  ".join(t for _, t in sorted(row)) for row in rows]

    @staticmethod
    def decode_base64(data: str) -> bytes:
        """容忍 data:image/png;base64, 前缀。"""
        if "," in data and data.strip().lower().startswith("data:"):
            data = data.split(",", 1)[1]
        return base64.b64decode(data)
