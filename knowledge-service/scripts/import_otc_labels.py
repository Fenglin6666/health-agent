"""把 NMPA《非处方药说明书范本》.doc 解析成结构化药品条目。

用法：
    python scripts/import_otc_labels.py 范本1.doc 范本2.doc ... --out /tmp/otc.json

为什么需要这个脚本：
    OTC 说明书是**官方公开**的（国家药监局按公告发布 .doc 附件，字段齐全：
    功能主治/适应症、用法用量、禁忌、不良反应、药物相互作用、注意事项）。
    但它不是"数据集"，是几百份散落的 Word 文件，所以需要一层解析。

为什么不用 python-docx / antiword：
    - python-docx 只认 .docx（OOXML）。.doc 是 OLE2 复合文档，格式完全不同；
    - antiword / LibreOffice 要额外装，目标机器上都没有。

    所以按 Word97 的存储特性直接取：中文正文以 UTF-16LE 存在 WordDocument 流里。
    扫描连续可读的 UTF-16LE 片段就能拿到正文，代价是会混进字体名、域代码这类噪声，
    用"高字节和低字节都是 ASCII 可打印字符"这条规则滤掉（"ttheme" 会被读成"瑴敨敭"）。

⚠️ 解析结果是**说明书原文**，不是我们的医学结论。字段映射见 FIELD_MAP。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

_ALLOWED = re.compile("[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef\u2000-\u206f\x20-\x7e\r\n\t]")

# 说明书里出现的字段标记。中成药写[功能主治]，化学药写[适应症]，两个都要认。
# 括号有全角半角两种，实测同一份文件里会混用。
_FIELD = re.compile(
    r"[\[【](药品名称|通用名称|成份|成分|性状|功能主治|适应症|适应证|规格|用法用量|"
    r"不良反应|禁忌|注意事项|药物相互作用|药物过量|贮藏|包装|有效期|执行标准|批准文号|"
    r"生产企业|药品上市许可持有人|妊娠及哺乳期妇女用药|儿童用药|老年用药|"
    r"药理作用|药理毒理|药代动力学|作用类别|说明书修订日期)[\]】]"
)

# 说明书字段 → 我们的 schema。None 表示这个字段不直接映射，另行处理。
FIELD_MAP = {
    "功能主治": "indications",
    "适应症": "indications",
    "适应证": "indications",
    "用法用量": "adult_dosage",
    "禁忌": "contraindications",
    "不良反应": "adverse_reactions",
    "药物相互作用": "interactions",
    "注意事项": "precautions",
    "规格": "spec",
    "成份": "ingredients",
    "成分": "ingredients",
    "性状": "appearance",
}


def _is_swapped_ascii(s: str) -> bool:
    """判断是不是"ASCII 被当成 UTF-16LE 读"产生的乱码。

    形如 "ttheme" → "瑴敨敭"：每个字符的高字节和低字节都落在 ASCII 可打印区。
    """
    if not s:
        return False
    hits = sum(1 for ch in s if 0x20 <= (ord(ch) >> 8) <= 0x7E and 0x20 <= (ord(ch) & 0xFF) <= 0x7E)
    return hits / len(s) > 0.9


def extract_text(path: str | Path) -> str:
    """从 .doc 里取出正文。"""
    data = Path(path).read_bytes()
    if data[:8] != OLE_MAGIC:
        raise ValueError(f"{path} 不是 Word97 复合文档（可能其实是 .docx 或 RTF）")

    segments: list[str] = []
    i, n = 0, len(data) - 1
    while i < n:
        buf: list[str] = []
        j = i
        while j < n:
            ch = data[j] | (data[j + 1] << 8)
            if ch == 0:
                break
            c = chr(ch)
            if _ALLOWED.match(c):
                buf.append(c)
                j += 2
            else:
                break
        if len(buf) >= 6:
            s = "".join(buf)
            if not _is_swapped_ascii(s):
                segments.append(s)
            i = j
        else:
            i += 2
    return "\n".join(segments)


def _clean(value: str) -> str:
    """去掉说明书排版残留：连续空白、项目符号、页眉页脚、字体表乱码。"""
    value = re.sub(r"[ \t\u3000]+", " ", value)
    # 字体/格式表被当成 UTF-16LE 读出来的整行乱码（"鶢鶖鶖鶖鶖…"），独占一行，整行删掉
    value = "\n".join(ln for ln in value.split("\n") if not re.search(r"(.)\1{2,}", ln))
    value = re.sub(r"\n{2,}", "\n", value)
    value = re.sub(r"(?m)^\s*(PAGE\s*\\\*.*|如有问题可与.*联系。?)\s*$", "", value)
    return value.strip(" ：:　\n")


def _suspect(value: str) -> bool:
    """含连续重复字，多半还有残留乱码，导入时要人工看一眼。"""
    return bool(re.search(r"(.)\1{2,}", value or ""))


# 药品名称后面常常紧跟"商品名称/英文名称/汉语拼音"这些空标签，粘在同一行
_NAME_STOP = re.compile(r"(商品名称|英文名称|汉语拼音|[\n\[【]|$)")


def _drug_name(value: str) -> str:
    m = re.search(r"通用名称[：:]\s*(.+?)" + _NAME_STOP.pattern, value)
    if m:
        return m.group(1).strip(" ：:　")
    # 没有"通用名称："前缀时，取第一行到下一个标签之前
    return value.split("\n")[0].split("商品名称")[0].strip(" ：:　")


def _split_by_drug(text: str) -> list[str]:
    """按 [药品名称] 切分。一份范本文件里常常有好几个品种。"""
    marks = [m.start() for m in re.finditer(r"[\[【]药品名称[\]】]", text)]
    if len(marks) <= 1:
        return [text]
    return [text[a:b] for a, b in zip(marks, marks[1:] + [len(text)])]


def parse_chunk(chunk: str) -> dict[str, str]:
    """把一个药品段切成 {schema 字段: 内容}。同名字段取第一次出现。"""
    out: dict[str, str] = {}
    marks = list(_FIELD.finditer(chunk))
    for idx, m in enumerate(marks):
        name = m.group(1)
        end = marks[idx + 1].start() if idx + 1 < len(marks) else len(chunk)
        value = _clean(chunk[m.end():end])
        if not value:
            continue
        if name in ("药品名称", "通用名称"):
            if "name" not in out:
                got = _drug_name(value)
                if got:
                    out["name"] = got
            continue
        key = FIELD_MAP.get(name)
        if key and key not in out:
            out[key] = value
    return out


def parse_file(path: str | Path) -> list[dict[str, str]]:
    text = extract_text(path)
    drugs: list[dict[str, str]] = []
    seen: set[str] = set()
    for chunk in _split_by_drug(text):
        d = parse_chunk(chunk)
        name = d.get("name", "")
        # 同一份文件里 [药品名称] 可能出现多次（正文 + 修订页），按名字去重
        if not name or name in seen:
            continue
        if not (d.get("indications") or d.get("adult_dosage")):
            continue
        seen.add(name)
        d["_source_file"] = Path(path).name
        if any(_suspect(v) for k, v in d.items() if not k.startswith("_")):
            d["_suspect"] = True
        drugs.append(d)
    return drugs


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="解析 NMPA 非处方药说明书范本 .doc")
    ap.add_argument("files", nargs="+", help=".doc 文件或目录")
    ap.add_argument("--out", help="输出 JSON 路径；不给就打到标准输出")
    args = ap.parse_args(argv)

    paths: list[Path] = []
    for f in args.files:
        p = Path(f)
        paths.extend(sorted(p.glob("*.doc")) if p.is_dir() else [p])

    results: list[dict] = []
    for p in paths:
        try:
            got = parse_file(p)
        except Exception as e:  # noqa: BLE001 - 单份失败不该中断整批
            print(f"[跳过] {p.name}: {e}", file=sys.stderr)
            continue
        print(f"[解析] {p.name}: {len(got)} 个品种", file=sys.stderr)
        results.extend(got)

    payload = json.dumps(results, ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).write_text(payload + "\n", encoding="utf-8")
        print(f"共 {len(results)} 个品种 → {args.out}", file=sys.stderr)
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
