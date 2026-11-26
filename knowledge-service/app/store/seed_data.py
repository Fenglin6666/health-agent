"""种子知识库。

⚠️ 这里的药品与疾病内容是**示例数据**，用于打通链路，不能作为医学依据。
生产环境必须换成 NMPA 说明书原文 + 临床指南，并保留出处与版本。

药品和非药品的形态不一样：说明书有固定字段，疾病/指南是一段论述。
所以药品数据在 `drugs.py` 里结构化存，这里只负责把它们拼成 Document——
`content` 由字段生成，检索和结构化返回共用同一份来源，不会漂移。
"""

from __future__ import annotations

from app.retrieval.bm25 import Document
from app.store.drugs import DRUGS, SOURCE as DRUG_SOURCE, to_content


def _title(d: dict) -> str:
    """标题。三种情况：

    - 人工整理的：药名 + 剂型，但药名里已经带剂型就不重复
      （"酮康唑乳膏" + "乳膏" 拼出来是"酮康唑乳膏乳膏"，还会让 BM25 把"乳膏"数两次）
    - openFDA 导入的：药名（英文通用名）。它们的 forms 是给药途径不是剂型，
      拼成"左甲状腺素口服"很怪，而英文名能帮上检索
    - 两者都带一个来源后缀，让模型知道这批数据的可信度不一样
    """
    name = d["name"]
    if d.get("name_en"):
        return f"{name}（{d['name_en']}）"
    form = d["forms"][0]
    suffix = "" if (form in name or name.endswith(form[0])) else form
    return f"{name}{suffix}（示例）"


def _drug_docs() -> list[Document]:
    return [
        Document(
            id=d["id"],
            title=_title(d),
            content=to_content(d),
            source=d.get("source", DRUG_SOURCE),
            tags=["drug", *d["tags"]],
            otc=d["otc"],
            drug={
                "name": d["name"],
                "forms": d["forms"],
                "otc": d["otc"],
                "indications": d["indications"],
                "adult_dosage": d["adult_dosage"],
                "contraindications": d["contraindications"],
                "adverse_reactions": d["adverse_reactions"],
                "interactions": d["interactions"],
                "duration": d["duration"],
                "stop_signs": d["stop_signs"],
            },
        )
        for d in DRUGS
    ]


SEED_DOCS: list[Document] = [
    *_drug_docs(),
    Document(
        id="disease-gerd",
        title="胃食管反流病（示例）",
        content=(
            "典型表现：烧心、反酸，多于餐后或平卧时加重。"
            "不典型表现：胸痛、慢性咳嗽、咽部异物感。"
            "需要警惕：吞咽困难、体重下降、呕血、黑便提示可能存在并发症。"
            "生活方式：避免饱餐、餐后 2 小时内不平卧、抬高床头、减少高脂饮食与饮酒。"
        ),
        source="临床指南摘要（示例数据）",
        tags=["disease", "digestive"],
    ),

    Document(
        id="disease-angina",
        title="稳定型心绞痛（示例）",
        content=(
            "典型表现：胸骨后压榨性疼痛，多于劳累、情绪激动、饱餐后发作，持续 3-5 分钟，休息或含服硝酸甘油可缓解。"
            "放射部位：左肩、左臂内侧、下颌、上腹部。"
            "危险信号：静息时发作、持续时间超过 20 分钟、伴大汗或濒死感，需立即排除急性心肌梗死。"
        ),
        source="临床指南摘要（示例数据）",
        tags=["disease", "cardio"],
    ),

    Document(
        id="disease-appendicitis",
        title="急性阑尾炎（示例）",
        content=(
            "典型表现：初始为脐周或上腹痛，数小时后转移至右下腹，呈持续性。"
            "伴随症状：食欲减退、恶心呕吐、低热。"
            "体征：右下腹麦氏点压痛、反跳痛。"
            "危险信号：全腹剧痛、板状腹提示穿孔，需急诊手术。"
        ),
        source="临床指南摘要（示例数据）",
        tags=["disease", "digestive", "emergency"],
    ),

    Document(
        id="redflag-chest",
        title="胸痛的危险信号（示例）",
        content=(
            "出现以下任一情况应立即就医：胸痛伴大汗、濒死感、呼吸困难；"
            "胸痛持续超过 20 分钟不缓解；胸痛伴晕厥或意识改变；"
            "突发撕裂样胸背痛。"
        ),
        source="急救指南摘要（示例数据）",
        tags=["redflag", "emergency"],
    ),

    Document(
        id="redflag-stroke",
        title="卒中的危险信号（示例）",
        content=(
            "突发以下任一表现，立即拨打 120：口角歪斜、言语不清或理解困难；"
            "一侧肢体无力或麻木（让患者双臂平举，一侧下坠）；单眼或双眼视物不清；"
            "突发眩晕伴行走不稳、平衡障碍；突发剧烈头痛，性质与以往不同。"
            "记住发病时间——缺血性卒中在 4.5 小时内有溶栓机会，时间就是大脑。"
            "等待救护车时：让患者平卧、头偏向一侧防止误吸，不要喂水喂药，不要自行降压。"
            "不要因为症状自行缓解就不去医院——短暂性脑缺血（TIA）是卒中的前兆。"
        ),
        source="卒中急救指南摘要（示例数据）",
        tags=["redflag", "emergency", "neuro"],
    ),

    Document(
        id="redflag-anaphylaxis",
        title="严重过敏反应的危险信号（示例）",
        content=(
            "出现以下表现提示过敏性休克，立即拨打 120：呼吸困难、喉头发紧或声音嘶哑；"
            "面唇舌肿胀；全身广泛风团伴瘙痒；头晕、眼前发黑、晕厥；"
            "恶心呕吐伴腹痛（过敏也可表现为消化道症状）。"
            "处理：立即停止接触可疑过敏原；如备有肾上腺素自动注射笔，立即大腿外侧肌注；"
            "平卧并抬高下肢，不要站立行走；有呕吐时侧卧防误吸。"
            "注意：抗组胺药（如氯雷他定）不能替代肾上腺素，对喉头水肿无效，不要因此延误就医。"
        ),
        source="过敏反应急救指南摘要（示例数据）",
        tags=["redflag", "emergency", "allergy"],
    ),

    Document(
        id="redflag-gi-bleeding",
        title="消化道出血的危险信号（示例）",
        content=(
            "出现以下情况需立即就医：呕血（鲜红色或咖啡渣样）；"
            "黑便（柏油样、发亮、有腥臭味）；便血或暗红色血便；"
            "伴头晕、心慌、乏力、起身时眼前发黑（提示失血量大）。"
            "处理：禁食禁水，立即前往急诊；不要自行服用止痛药（非甾体抗炎药会加重出血）；"
            "正在服用抗凝药或抗血小板药（华法林、阿司匹林、氯吡格雷）要主动告知医生。"
            "注意：黑便需要至少 50-100ml 出血才会出现，看到黑便说明出血量已不少。"
        ),
        source="消化道出血诊疗指南摘要（示例数据）",
        tags=["redflag", "emergency", "digestive"],
    ),

    Document(
        id="lab-cbc",
        title="血常规：血红蛋白与红细胞指数（示例）",
        content=(
            "血红蛋白参考范围：成年男性 130-175 g/L，成年女性 115-150 g/L，低于下限提示贫血。"
            "平均红细胞体积 MCV 参考 82-100 fL：低于 82 为小细胞性贫血，常见于缺铁、慢性病、地中海贫血；"
            "高于 100 为大细胞性贫血，常见于维生素 B12 或叶酸缺乏。"
            "红细胞压积 HCT 参考：男性 40-50%，女性 35-45%，与血红蛋白变化通常一致。"
            "发现小细胞性贫血应进一步查铁蛋白、总铁结合力，明确是否为缺铁。"
        ),
        source="检验医学参考（示例数据）",
        tags=["lab", "hematology"],
    ),

    Document(
        id="lab-lipid",
        title="血脂四项解读（示例）",
        content=(
            "总胆固醇 TC 合适水平低于 5.2 mmol/L，5.2-6.2 为边缘升高，高于 6.2 为升高。"
            "甘油三酯 TG 合适水平低于 1.7 mmol/L，1.7-2.3 为边缘升高，高于 2.3 为升高。"
            "低密度脂蛋白胆固醇 LDL-C 理想水平低于 2.6 mmol/L，一般人群目标低于 3.4 mmol/L，"
            "已有冠心病或糖尿病者目标更低（低于 1.8 甚至 1.4）。"
            "高密度脂蛋白 HDL-C 低于 1.0 mmol/L 为偏低，是心血管风险因素。"
            "生活方式干预：减少饱和脂肪与精制糖、每周 150 分钟中等强度运动、控制体重。"
        ),
        source="血脂管理指南摘要（示例数据）",
        tags=["lab", "lipid"],
    ),

    Document(
        id="lab-glucose",
        title="血糖指标解读（示例）",
        content=(
            "空腹血糖参考 3.9-6.1 mmol/L。6.1-7.0 为空腹血糖受损，两次≥7.0 可诊断糖尿病。"
            "糖化血红蛋白 HbA1c 反映近 2-3 个月平均血糖，正常低于 6.0%，6.0-6.5 为糖尿病前期。"
            "单次空腹血糖升高不能确诊，需结合餐后血糖或重复检测。"
        ),
        source="糖尿病诊疗指南摘要（示例数据）",
        tags=["lab", "endocrine"],
    ),
]


# 极简图谱：实体 → 关系列表。生产环境换 Neo4j。
SEED_GRAPH: dict[str, list[dict[str, str]]] = {
    "gerd": [
        {"relation": "HAS_SYMPTOM", "target": "烧心"},
        {"relation": "HAS_SYMPTOM", "target": "反酸"},
        {"relation": "TREATED_BY", "target": "奥美拉唑"},
        {"relation": "BELONGS_TO", "target": "消化内科"},
    ],
    "angina": [
        {"relation": "HAS_SYMPTOM", "target": "胸骨后压榨性疼痛"},
        {"relation": "HAS_SYMPTOM", "target": "活动后加重"},
        {"relation": "BELONGS_TO", "target": "心血管内科"},
        {"relation": "RED_FLAG_OF", "target": "急性心肌梗死"},
    ],
    "appendicitis": [
        {"relation": "HAS_SYMPTOM", "target": "转移性右下腹痛"},
        {"relation": "HAS_SYMPTOM", "target": "麦氏点压痛"},
        {"relation": "BELONGS_TO", "target": "普通外科"},
    ],
    "阿莫西林": [
        {"relation": "CONTRAINDICATED", "target": "青霉素过敏"},
        {"relation": "INTERACTS_WITH", "target": "甲氨蝶呤"},
    ],
    "奥美拉唑": [
        {"relation": "INTERACTS_WITH", "target": "氯吡格雷"},
        {"relation": "TREATS", "target": "胃溃疡"},
    ],
}
