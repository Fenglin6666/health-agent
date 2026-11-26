"""药品结构化数据（加载器）。

**为什么单独一个文件**：药品和非药品的知识形态不一样。说明书有固定字段
（适应症、用法、禁忌、不良反应、相互作用），而疾病/指南是一段连续的论述。
把药品硬塞进"一段文本"里，`drug_info` 工具就只能把原文再念一遍——
那就不是工具，是复读机。

**为什么数据放在 JSON 而不是写在这个文件里**：药品库一定会换源。
写死在 .py 里，换源就要改代码、走一次评审、重跑一遍测试；放在 JSON 里，
换源只是换一个文件。字段校验在这里做，所以换源时字段缺失会在启动时就炸，
而不是等到用户问到那味药才炸。

**两个数据文件，两层来源**：
- `data/drugs.json` —— 人工整理，中文，35 味常用药。覆盖常见自诊自疗场景。
- `data/drugs-openfda.json` —— 从 openFDA 导入（美国市场，公有领域），45 味。
  主要是处方药，用来回答"我正在吃 X，能不能再吃 Y"。

两层合并时人工整理的优先：同一个通用名以中文版为准，因为剂量和规格是按
中国市场写的。openFDA 那批只补空位。

⚠️ 两批都不能当医学依据：中文版是示例数据，openFDA 版是美国市场标签。
"""

from __future__ import annotations

import json
from pathlib import Path

_DATA_DIR = Path(__file__).parent / "data"

#: 数据文件，按优先级排列。同名药品以靠前的为准。
_DATA_FILES: tuple[tuple[str, str], ...] = (
    ("drugs.json", "人工整理"),
    ("drugs-openfda.json", "openFDA"),
)

#: 说明书必须有的字段。少一个就不是说明书，`drug_info` 会给出残缺结论。
REQUIRED_FIELDS: tuple[str, ...] = (
    "id",
    "name",
    "forms",
    "otc",
    "tags",
    "indications",
    "adult_dosage",
    "contraindications",
    "adverse_reactions",
    "interactions",
    "duration",
    "stop_signs",
)

#: 允许为空的字段。真实说明书里这几项常常不单列——
#: openFDA 的 OTC 标签是 Drug Facts 格式，没有"不良反应/相互作用"两栏，
#: "疗程"和"停药指征"也写在 Warnings / Stop use 里。硬填只能造假。
#: 但**适应症、用法、禁忌不能空**：空着会让模型以为"这药没禁忌"，
#: 而实际是这份标签没写——缺信息不能表现成"没有"。
OPTIONAL_FIELDS: frozenset[str] = frozenset(
    {"adverse_reactions", "interactions", "duration", "stop_signs"}
)


def _read(filename: str) -> tuple[str, list[dict]]:
    path = _DATA_DIR / filename
    raw = json.loads(path.read_text(encoding="utf-8"))
    meta = raw.get("_meta") or {}
    return meta.get("source", "未知来源"), list(raw.get("drugs") or [])


def _validate(d: dict, where: str, i: int) -> None:
    missing = [f for f in REQUIRED_FIELDS if f not in d]
    if missing:
        raise ValueError(f"{where} 第 {i + 1} 条（{d.get('name', '?')}）缺字段: {missing}")
    blank = [f for f in REQUIRED_FIELDS if f not in OPTIONAL_FIELDS and not str(d[f]).strip()]
    if blank:
        raise ValueError(f"{where} 中 {d['name']} 的字段为空: {blank}")
    if not isinstance(d["otc"], bool):
        # otc 直接决定 agent 敢不敢推荐。类型错了宁可启动失败。
        raise ValueError(f"{where} 中 {d['name']} 的 otc 必须是 bool，实际是 {type(d['otc'])}")
    if not isinstance(d["forms"], list) or not d["forms"]:
        raise ValueError(f"{where} 中 {d['name']} 的 forms 必须是非空列表")
    if not isinstance(d.get("aliases", []), list):
        raise ValueError(f"{where} 中 {d['name']} 的 aliases 必须是列表")


def _load() -> list[dict]:
    merged: list[dict] = []
    seen_id: set[str] = set()
    seen_name: set[str] = set()

    for filename, label in _DATA_FILES:
        source, drugs = _read(filename)
        for i, d in enumerate(drugs):
            _validate(d, filename, i)
            d.setdefault("aliases", [])
            # 单条可以覆盖文件级的出处（openFDA 的标签和人工整理不是一回事）
            d.setdefault("source", source)
            d.setdefault("tier", label)

            if d["id"] in seen_id:
                raise ValueError(f"{filename} 中 id 与已有药品重复: {d['id']}")
            if d["name"] in seen_name:
                # 同一通用名以先加载的为准（人工整理在前）
                continue
            seen_id.add(d["id"])
            seen_name.add(d["name"])
            merged.append(d)

    if not merged:
        raise ValueError("药品库是空的，检查 data/ 下的 JSON")
    return merged


DRUGS: list[dict] = _load()

#: 兼容旧引用：整体出处。单条出处看 `d["source"]`。
SOURCE = "NMPA 说明书（示例数据，非原文） / openFDA 药品标签（美国市场，公有领域）"


def to_content(d: dict) -> str:
    """把结构化字段拼成一段可检索的文本。

    顺序刻意和说明书一致：适应症 → 用法 → 禁忌 → 注意事项 → 不良反应。
    检索时"禁忌"和"相互作用"是最容易被问到的部分，放前面命中率高。

    **别名放在最前面**：用户说"脚气"，说明书只写"足癣"，不补这层就召回不到。
    别名同时是给模型看的——它看到"脚气、脚癣、足癣"就知道该怎么把话说回人话。
    """
    alias = "、".join(d.get("aliases") or [])
    prefix = f"常见叫法：{alias}。" if alias else ""
    parts = [
        f"适应症：{d['indications']}",
        f"用法用量：{d['adult_dosage']}",
        f"禁忌：{d['contraindications']}",
        f"注意事项与相互作用：{d['interactions']}",
        f"常见不良反应：{d['adverse_reactions']}",
    ]
    # 空字段不拼进去。写"疗程："后面跟空白，模型会以为说明书说了什么但被截断了
    if d.get("duration"):
        parts.append(f"疗程：{d['duration']}")
    if d.get("stop_signs"):
        parts.append(f"停药指征：{d['stop_signs']}")
    return prefix + "".join(parts)
