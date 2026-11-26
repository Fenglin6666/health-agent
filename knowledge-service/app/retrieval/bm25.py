"""BM25 检索的极简实现。

为什么自己写而不是引 rank_bm25：只需要 40 行，少一个依赖。
中文按字符 bigram 切，不引分词器——医疗短句上效果够用，且没有词典维护成本。
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

_TOKEN_RE = re.compile(r"[\s，。？！、,.?!~…—\-]+")


def tokenize(text: str) -> list[str]:
    """字符 bigram 切分。单字保留，避免短查询全空。"""
    s = _TOKEN_RE.sub("", text.lower())
    if len(s) <= 1:
        return [s] if s else []
    return [s[i : i + 2] for i in range(len(s) - 1)]


@dataclass
class Document:
    id: str
    title: str
    content: str
    source: str = ""
    tags: list[str] = field(default_factory=list)
    # 处方属性。True=非处方药，False=处方药，None=非药品/未标注。
    # 这个字段是"能不能推荐"的唯一依据——不让模型凭记忆判断，
    # 因为模型对"某个药在中国是不是 OTC"的记忆非常不可靠（同一成分不同剂型都不同）。
    otc: bool | None = None
    # 药品的结构化字段（适应症/用法/禁忌/不良反应/相互作用/疗程/停药指征）。
    # 非药品为 None。存结构化是为了让 drug_info 工具能直接返回字段，
    # 而不是把说明书原文再念一遍——那样工具就没有存在意义。
    drug: dict | None = None


class BM25Index:
    def __init__(self, k1: float = 1.5, b: float = 0.75) -> None:
        self.k1 = k1
        self.b = b
        self.docs: list[Document] = []
        self._tokens: list[list[str]] = []
        self._tf: list[Counter] = []
        self._df: Counter = Counter()
        self._avg_len: float = 0.0

    def add(self, docs: list[Document]) -> None:
        for doc in docs:
            tokens = tokenize(doc.title + " " + doc.content)
            self.docs.append(doc)
            self._tokens.append(tokens)
            tf = Counter(tokens)
            self._tf.append(tf)
            for term in tf:
                self._df[term] += 1
        total = sum(len(t) for t in self._tokens)
        self._avg_len = total / len(self._tokens) if self._tokens else 0.0

    def search(self, query: str, top_k: int = 3) -> list[tuple[Document, float]]:
        if not self.docs:
            return []
        q_tokens = tokenize(query)
        if not q_tokens:
            return []

        n = len(self.docs)
        scored: list[tuple[Document, float]] = []
        for i, doc in enumerate(self.docs):
            dl = len(self._tokens[i])
            score = 0.0
            for term in q_tokens:
                f = self._tf[i].get(term, 0)
                if f == 0:
                    continue
                df = self._df.get(term, 0)
                idf = math.log(1 + (n - df + 0.5) / (df + 0.5))
                denom = f + self.k1 * (1 - self.b + self.b * dl / (self._avg_len or 1))
                score += idf * f * (self.k1 + 1) / denom
            if score > 0:
                scored.append((doc, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k]

    def __len__(self) -> int:
        return len(self.docs)
