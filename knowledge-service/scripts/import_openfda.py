"""从 openFDA 导入药品说明书，产出 `data/drugs-openfda.json`。

用法：
    python scripts/import_openfda.py                # 全量重跑，写文件
    python scripts/import_openfda.py --dry-run      # 只打印，不写文件

为什么要这个脚本：
    NMPA 的说明书范本拿不到（站点访问不了），而人工整理的 35 味覆盖不了
    "我正在吃 X，能不能再吃 Y"这类问题——那需要大量处方药的相互作用信息。
    openFDA 的药品标签是**美国联邦政府作品，公有领域（CC0）**，可以随便用。

为什么按"通用名清单"抓，而不是全量下载：
    openFDA 有 20 多万份标签，绝大多数是同一成分的不同包装。全量导进来
    索引会爆，而且没有一条会被问到。这里只抓一份人工挑过的清单。

⚠️ 数据是**美国市场**的标签：
    - 药名、规格、剂量按美国市场定，和中国说明书不完全一致
    - OTC 标签是 Drug Facts 格式（Uses/Warnings/Directions），没有"禁忌/不良反应/
      相互作用"三栏，只能用 do_not_use / warnings / ask_doctor 近似
    - 处方药标签是 SPL 格式，五个临床字段齐全，质量最好
    正文保留英文原文，不做机器翻译——翻译过的说明书不能当依据用。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://api.fda.gov/drug/label.json"
OUT = Path(__file__).resolve().parent.parent / "app" / "store" / "data" / "drugs-openfda.json"

# 每个字段截断长度。标签原文动辄几千字，全塞进索引既没必要也拖慢检索；
# 240 字是喂给模型的片段长度，800 字足够模型判断"这药对不对症"。
MAX_FIELD = 800

# 人工挑的清单：(openFDA 通用名, 中文通用名, 是否 OTC, 标签, 中文检索词)
#
# 选药标准：中国门诊/药房高频 + 相互作用风险高。
#
# **为什么每味药要手写中文检索词**：正文是英文原文，"痛风"打不中 "gout"，
# "他汀"打不中 "atorvastatin"。BM25 跨不了语言，向量模型也不稳（实测
# "痛风发作吃什么药"召回的是止痛药，不是别嘌醇/秋水仙碱）。这层关键词
# 拼进 aliases，中文问句才有字面锚点。**这是英文数据的必要补偿，不是可选项。**
CATALOG: list[tuple[str, str, bool, list[str], list[str]]] = [
    # ---------- 处方药：五个临床字段齐全，主要用来判相互作用 ----------
    ("levothyroxine", "左甲状腺素", False, ["thyroid", "hormone"], ["甲减", "甲状腺功能减退", "甲状腺"]),
    ("lisinopril", "赖诺普利", False, ["antihypertensive", "acei"], ["高血压", "降压药", "心衰"]),
    ("losartan", "氯沙坦", False, ["antihypertensive", "arb"], ["高血压", "降压药"]),
    ("metoprolol", "美托洛尔", False, ["antihypertensive", "beta-blocker"], ["高血压", "心率快", "心绞痛", "倍他乐克"]),
    ("furosemide", "呋塞米", False, ["diuretic"], ["水肿", "利尿", "心衰"]),
    ("spironolactone", "螺内酯", False, ["diuretic", "potassium-sparing"], ["水肿", "利尿", "醛固酮"]),
    ("clopidogrel", "氯吡格雷", False, ["antiplatelet"], ["抗血小板", "支架", "波立维"]),
    ("rivaroxaban", "利伐沙班", False, ["anticoagulant"], ["抗凝", "血栓", "房颤"]),
    ("apixaban", "阿哌沙班", False, ["anticoagulant"], ["抗凝", "血栓", "房颤"]),
    ("digoxin", "地高辛", False, ["cardiac-glycoside"], ["心衰", "房颤", "强心"]),
    ("amiodarone", "胺碘酮", False, ["antiarrhythmic"], ["心律失常", "房颤", "早搏"]),
    ("sertraline", "舍曲林", False, ["antidepressant", "ssri"], ["抑郁", "抗抑郁", "焦虑"]),
    ("escitalopram", "艾司西酞普兰", False, ["antidepressant", "ssri"], ["抑郁", "抗抑郁", "焦虑"]),
    ("alprazolam", "阿普唑仑", False, ["sedative", "benzodiazepine"], ["焦虑", "失眠", "安定类"]),
    ("zolpidem", "唑吡坦", False, ["sedative", "hypnotic"], ["失眠", "安眠药"]),
    ("prednisone", "泼尼松", False, ["corticosteroid"], ["激素", "强的松", "抗炎"]),
    ("allopurinol", "别嘌醇", False, ["gout"], ["痛风", "高尿酸", "尿酸高"]),
    ("colchicine", "秋水仙碱", False, ["gout"], ["痛风", "痛风发作"]),
    ("tamsulosin", "坦索罗辛", False, ["bph"], ["前列腺", "排尿困难", "前列腺增生"]),
    ("montelukast", "孟鲁司特", False, ["asthma"], ["哮喘", "过敏性鼻炎", "咳嗽变异性哮喘"]),
    ("albuterol", "沙丁胺醇", False, ["bronchodilator", "asthma"], ["哮喘", "喘息", "支气管"]),
    ("budesonide", "布地奈德", False, ["corticosteroid", "inhaled"], ["哮喘", "吸入激素", "鼻炎"]),
    ("azithromycin", "阿奇霉素", False, ["antibiotic", "macrolide"], ["支原体", "抗生素", "咽炎", "支气管炎"]),
    ("levofloxacin", "左氧氟沙星", False, ["antibiotic", "quinolone"], ["抗生素", "尿路感染", "肺炎"]),
    ("doxycycline", "多西环素", False, ["antibiotic", "tetracycline"], ["抗生素", "痤疮", "支原体"]),
    ("cephalexin", "头孢氨苄", False, ["antibiotic", "cephalosporin"], ["抗生素", "头孢", "皮肤感染"]),
    ("ciprofloxacin", "环丙沙星", False, ["antibiotic", "quinolone"], ["抗生素", "尿路感染", "腹泻"]),
    ("acyclovir", "阿昔洛韦", False, ["antiviral"], ["疱疹", "带状疱疹", "抗病毒"]),
    ("oseltamivir", "奥司他韦", False, ["antiviral"], ["流感", "甲流", "抗病毒", "达菲"]),
    ("pantoprazole", "泮托拉唑", False, ["ppi", "acid-suppressant"], ["胃酸", "抑酸", "胃溃疡", "反流"]),
    ("ondansetron", "昂丹司琼", False, ["antiemetic"], ["止吐", "恶心", "呕吐"]),
    ("sitagliptin", "西格列汀", False, ["antidiabetic"], ["糖尿病", "降糖"]),
    ("glipizide", "格列吡嗪", False, ["antidiabetic", "sulfonylurea"], ["糖尿病", "降糖"]),
    ("gabapentin", "加巴喷丁", False, ["anticonvulsant", "neuropathic-pain"], ["神经痛", "带状疱疹后遗神经痛", "癫痫"]),
    ("sildenafil", "西地那非", False, ["pde5"], ["勃起功能障碍", "阳痿"]),
    # ---------- 非处方药：Drug Facts 格式，字段偏少 ----------
    ("loperamide", "洛哌丁胺", True, ["antidiarrheal"], ["止泻", "拉肚子", "腹泻", "易蒙停"]),
    ("diphenhydramine", "苯海拉明", True, ["antihistamine", "sedating"], ["抗过敏", "止痒", "助眠", "晕车"]),
    ("chlorpheniramine", "氯苯那敏", True, ["antihistamine", "sedating"], ["抗过敏", "扑尔敏", "感冒"]),
    ("guaifenesin", "愈创甘油醚", True, ["expectorant"], ["化痰", "咳嗽", "有痰"]),
    ("meclizine", "美克洛嗪", True, ["antiemetic", "antihistamine"], ["晕车", "眩晕", "恶心"]),
    ("miconazole", "咪康唑", True, ["antifungal", "topical"], ["脚气", "真菌", "霉菌", "抗真菌"]),
    ("bismuth subsalicylate", "次水杨酸铋", True, ["antidiarrheal", "antacid"], ["拉肚子", "腹泻", "胃胀"]),
    ("simethicone", "二甲硅油", True, ["antiflatulent"], ["胀气", "腹胀", "胃胀"]),
]

# openFDA 的字段名 → 我们的 schema。按优先级排，先命中先用。
_RX_MAP = {
    "indications": ["indications_and_usage"],
    "dosage": ["dosage_and_administration"],
    "contraindications": ["contraindications"],
    "adverse": ["adverse_reactions"],
    "interactions": ["drug_interactions"],
    "stop": ["warnings_and_cautions", "boxed_warning"],
}

# OTC 是 Drug Facts 格式，没有上面那几栏，只能拼
_OTC_MAP = {
    "indications": ["uses", "purpose"],
    "dosage": ["directions", "dosage_and_administration"],
    "contraindications": ["do_not_use", "ask_doctor"],
    "adverse": ["warnings"],
    "interactions": ["ask_doctor_or_pharmacist", "when_using"],
    "stop": ["stop_use"],
}


def _text(rec: dict, keys: list[str], limit: int = MAX_FIELD) -> str:
    """按优先级取第一个非空字段，拼成一段，超长截断。"""
    parts: list[str] = []
    for k in keys:
        for v in rec.get(k) or []:
            v = " ".join(str(v).split())
            if v and v not in parts:
                parts.append(v)
    if not parts:
        return ""
    out = " ".join(parts)
    return out if len(out) <= limit else out[:limit].rstrip() + "…"


def _first(rec: dict, *keys: str) -> str:
    for k in keys:
        v = rec.get(k)
        if isinstance(v, list) and v:
            return str(v[0]).strip()
        if isinstance(v, str) and v.strip():
            return v.strip()
    return ""


# openFDA 的 openfda.route 是给药途径。标签里没有干净的"剂型"字段
# （dosage_forms_and_strengths 是一整段，含规格），所以用途径当中文剂型提示。
_ROUTE_CN = {
    "ORAL": "口服",
    "TOPICAL": "外用",
    "INTRAVENOUS": "静脉注射",
    "INTRAMUSCULAR": "肌内注射",
    "SUBCUTANEOUS": "皮下注射",
    "RESPIRATORY (INHALATION)": "吸入",
    "OPHTHALMIC": "滴眼",
    "OTIC": "滴耳",
    "NASAL": "鼻用",
    "RECTAL": "直肠给药",
    "VAGINAL": "阴道给药",
    "TRANSDERMAL": "透皮贴剂",
    "SUBLINGUAL": "舌下含服",
    "ORAL-28": "口服",
}


def _form(openfda: dict) -> str:
    route = (openfda.get("route") or [""])[0].upper()
    if route in _ROUTE_CN:
        return _ROUTE_CN[route]
    # 没给途径时退回英文原文，别编一个中文剂型出来
    return route.title() if route else "未标注"


def _score(rec: dict, otc: bool) -> int:
    """挑"信息最全"的那份标签。

    openFDA 同一成分常有几十份标签（不同厂家/包装）。直接取第一条经常拿到
    只有一行用量的简版，所以要按关键字段的齐全度打分。
    """
    fields = _OTC_MAP if otc else _RX_MAP
    return sum(1 for keys in fields.values() if any(rec.get(k) for k in keys))


def fetch(generic: str, otc: bool) -> dict | None:
    query = f'openfda.generic_name:"{generic}"'
    if otc:
        query += ' AND openfda.product_type:"HUMAN OTC DRUG"'
    url = f"{API}?search={urllib.parse.quote(query)}&limit=20"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:  # 查不到这个成分
            return None
        raise
    results = body.get("results") or []
    if not results:
        return None
    return max(results, key=lambda r: _score(r, otc))


def to_entry(generic: str, cn: str, otc: bool, tags: list[str],
             cn_keywords: list[str], rec: dict) -> dict:
    fields = _OTC_MAP if otc else _RX_MAP
    openfda = rec.get("openfda") or {}

    indications = _text(rec, fields["indications"])
    dosage = _text(rec, fields["dosage"])
    contra = _text(rec, fields["contraindications"])
    adverse = _text(rec, fields["adverse"])
    interactions = _text(rec, fields["interactions"])
    stop = _text(rec, fields["stop"])

    forms = [_form(openfda)]
    brand = _first(rec, "brand_name")
    aliases = [generic.upper()]
    if brand and brand.upper() != generic.upper():
        aliases.append(brand)
    aliases.extend(cn_keywords)

    return {
        "id": "fda-" + generic.lower().replace(" ", "-"),
        "name": cn,
        "forms": forms,
        "otc": otc,
        "tags": tags,
        "aliases": aliases,
        "indications": indications or "（该标签未载明适应症）",
        "adult_dosage": dosage or "",
        "contraindications": contra or "",
        "adverse_reactions": adverse or "",
        "interactions": interactions or "",
        "duration": "",
        "stop_signs": stop or "",
        "source": "openFDA 药品标签（美国市场，公有领域）",
        "name_en": generic.upper(),
    }


#: 必须有内容的字段。缺任何一个这条就不入库。
#: 尤其是 contraindications——空着会让模型以为"这药没有禁忌"，
#: 而实际是这份标签没写。**缺信息不能表现成"没有"**。
REQUIRED = ("indications", "adult_dosage", "contraindications")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.4, help="请求间隔秒数，避免触发限流")
    args = ap.parse_args(argv)

    entries: list[dict] = []
    skipped: list[str] = []
    for i, (generic, cn, otc, tags, cn_keywords) in enumerate(CATALOG, 1):
        try:
            rec = fetch(generic, otc)
        except Exception as e:  # noqa: BLE001 - 单条失败不中断整批
            print(f"[{i}/{len(CATALOG)}] {generic}: 请求失败 {e}", file=sys.stderr)
            time.sleep(2)
            continue
        if rec is None:
            print(f"[{i}/{len(CATALOG)}] {generic}: 无结果，跳过", file=sys.stderr)
            skipped.append(cn)
            continue
        e = to_entry(generic, cn, otc, tags, cn_keywords, rec)
        missing = [k for k in REQUIRED if not e[k]]
        if missing:
            print(f"[{i}/{len(CATALOG)}] {cn:8s} {generic:20s} 缺 {missing}，跳过", file=sys.stderr)
            skipped.append(cn)
            continue
        filled = sum(1 for k in ("indications", "adult_dosage", "contraindications",
                                 "adverse_reactions", "interactions", "stop_signs") if e[k])
        print(f"[{i}/{len(CATALOG)}] {cn:8s} {generic:20s} 字段 {filled}/6", file=sys.stderr)
        entries.append(e)
        time.sleep(args.sleep)

    payload = {
        "_meta": {
            "source": "openFDA 药品标签（美国市场，公有领域 CC0）",
            "version": time.strftime("%Y-%m-%d"),
            "note": "由 scripts/import_openfda.py 生成，请勿手工编辑。正文为英文原文，"
                    "未做翻译——翻译过的说明书不能当依据。药名/规格/剂量按美国市场，"
                    "与中国说明书可能不一致。",
        },
        "drugs": entries,
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.dry_run:
        print(text[:2000])
        print(f"\n[dry-run] 共 {len(entries)} 条，未写文件", file=sys.stderr)
    else:
        OUT.write_text(text, encoding="utf-8")
        print(f"共 {len(entries)} 条 → {OUT}", file=sys.stderr)
    if skipped:
        print(f"跳过 {len(skipped)} 条（标签不完整）：{'、'.join(skipped)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
