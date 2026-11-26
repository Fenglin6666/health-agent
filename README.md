# Health Agent

个人向健康管家 Agent —— 生产级参照实现。

## 结构

```
.
├── docs/                 设计文档与开发规范
├── health-agent/         Java 主服务（编排、Agent 内核、业务）
├── knowledge-service/    Python 知识服务（检索、图谱、解析）
├── web/                  Vue 前端
├── docker-compose.yml    本地依赖（MySQL / Redis / ES / Neo4j）
└── pom.xml               父 POM
```

## 关键设计

- **Manager + Execute 两级结构**：Manager 只做路由（ROUTE / CLARIFY / ANSWER），Execute 按能力切（问诊 / 问药 / 报告 / 地点 / 科普）
- **规则只拦域外，意图全交 LLM**：红旗扫描（安全闸门）→ 域外过滤（写代码/解方程等明确无关的）→ Manager 判定意图。规则不碰"这是问药还是问诊"——判错整条链路都错，而且用户看不出来
- **记忆四层 + append-only 账本**：transcripts / notes / representations / injected，关键事实只追加不覆盖
- **矛盾检测**：新事实与投影冲突时产出"待确认"，写进画像最前面，用户确认后消失
- **Safety 独立且不用 LLM**：规则引擎 + KG 校验，输出前必过
- **问药边界由知识库决定**：`otc` 字段为 true 才允许推荐具体品种，模型不凭记忆判断处方属性
- **会话不限轮次，只限单轮任务步数**：轮次上限会逼用户重开、丢上下文；单轮步数上限防死循环
- **检索三层**：BM25 + kNN → RRF 融合 → 交叉编码器重排（只提升正分候选）
- **延迟预算**：P95 < 6s，TTFT < 2s，逐环节分配

文档地图见 `docs/INDEX.md`（需求 → 技术方案 → 详细设计 → 测评 → 归因）；架构决策见 `docs/health-agent-design.md`，开发规范见 `docs/dev-standards.md`。

## 快速开始

前置：JDK 21、Node 22、Python 3.11+、Docker。

```bash
# 1. 启动中间件（本地已有 ES / Neo4j 可跳过）
docker compose up -d mysql redis
docker compose --profile knowledge up -d elasticsearch neo4j

# 2. 起知识服务（会自动建索引、导图谱、后台预热重排模型）
cd knowledge-service
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt        # Windows
# .venv/bin/python -m pip install -r requirements.txt              # macOS / Linux
# OCR 需要单独装（包的元数据卡 Python <3.13，但 3.13 上实测可用）：
# .venv/Scripts/python.exe -m pip install rapidocr-onnxruntime --ignore-requires-python
.venv/Scripts/python.exe -m uvicorn app.main:app --port 8001

# 3. 起模型网关（凭证只在这里）
export HA_LLM_API_KEY=sk-xxx
java -jar llm-gateway/target/llm-gateway-0.1.0-SNAPSHOT.jar --server.port=8081

# 4. 起主服务（不带模型密钥，走网关）
# 注意：如果环境里还留着 HA_LLM_BASE_URL=https://api.deepseek.com/v1，
# 它会覆盖默认值、绕过网关。用网关时把它删掉或指向网关。
unset HA_LLM_BASE_URL HA_LLM_API_KEY
java -jar health-agent/target/health-agent-0.1.0-SNAPSHOT.jar --server.port=8080

# 5. 起前端
cd web && npm install && npm run dev
```

主服务 `http://localhost:8080`，模型网关 `http://localhost:8081`，前端 `http://localhost:5173`，知识服务 `http://localhost:8001`。

知识服务会优先连 ES（`ES_URL`，默认 `localhost:9200`）和 Neo4j（`NEO4J_URL`，默认 `localhost:7474`），
连不上自动回退到内存实现——**不会因为中间件没起就起不来**。`/health` 会显示当前用的是哪套后端。

这里和主服务的策略**有意不同**：知识服务是只读索引，回退只影响召回质量，不丢用户数据；
主服务存的是会话和健康档案，回退等于把数据扔进内存，所以那边连不上就直接启动失败。

## 环境变量

配置前缀是 `ha.llm` 而不是 `llm`——很多宿主环境（IDE、容器、CI）会注入 `LLM_API_KEY` 这个通用变量名，Spring 的 relaxed binding 会把它直接映射到 `llm.api-key`，导致配置被静默覆盖。加一层前缀隔离。

| 变量 | 说明 | 默认 |
|---|---|---|
| `HA_LLM_BASE_URL` | core 调用的模型地址。**用网关时不要设**，默认 `http://localhost:8081/v1` | 网关地址 |
| `HA_LLM_API_KEY` | 模型密钥，**只配给 llm-gateway** | 空 |
| `HA_LLM_UPSTREAM_URL` | 网关的上游地址（含版本段） | `https://api.deepseek.com/v1` |
| `HA_LLM_FALLBACK_MODEL` | 网关的兜底模型，空表示不兜底 | 空 |
| `HA_GATEWAY_TOKEN` | 内网调用令牌，部署时必须配 | 空 |
| `HA_LLM_MODEL_MANAGER` | Manager 用的快模型 | `deepseek-chat` |
| `HA_LLM_MODEL_EXECUTE` | Execute 用的强模型 | `deepseek-chat` |
| `HA_LLM_OFFLINE` | 强制离线（不发真实请求） | `false` |
| `HA_AMAP_KEY` | 高德 Web 服务 key，用于附近药店/医院 | 空（不配则 POI 不可用） |
| `KNOWLEDGE_BASE_URL` | 知识服务地址 | `http://localhost:8001` |
| `ES_URL` / `NEO4J_URL` | 知识服务用的 ES / Neo4j 地址 | `localhost:9200` / `localhost:7474` |
| `VOLC_ARK_API_KEY` | 火山方舟 key，**向量检索的唯一实现**；不配则降级为 BM25 | 空 |
| `VOLC_EMBED_MODEL` | 向量模型 | `doubao-embedding-vision-251215` |
| `RERANK_ENABLED` | 是否启用重排（关掉可省 0.6-0.9s） | `true` |
| `RERANK_MODEL` | 重排模型 | `BAAI/bge-reranker-base` |
| `RERANK_THRESHOLD` | 只提升高于此分数的候选 | `0` |
| `RERANK_DROP_BELOW` | 全体候选都低于此分时返回空（避免把无关文档当证据） | `-5` |
| `HA_DB_URL` | MySQL 连接串 | `jdbc:mysql://localhost:3307/health_agent?...` |
| `HA_DB_USER` / `HA_DB_PASSWORD` | 数据库账号 | `ha` / `ha_local_dev` |
| `HA_PERSISTENCE_MODE` | `jdbc`（默认，连不上就启动失败）/ `memory`（显式关闭持久化，仅本地调试） | `jdbc` |
| `HA_AGENT_HISTORY_WINDOW` | 每次调模型带多少条历史消息（**上下文窗口，不是轮次上限**） | `20` |
| `HA_AGENT_MAX_STEPS` | 单轮任务默认最大步数 | `4` |

**如果本机有 `HTTP_PROXY`**：启动服务时加 `NO_PROXY=localhost,127.0.0.1`，否则本地服务互调会走代理返回 502。这个坑实测踩过。

### 向量模型

只有一种实现：火山方舟 `doubao-embedding-vision-251215`，2048 维，支持图片。

**为什么不做本地/云端两套**：向量和模型强绑定。512 维和 2048 维不只是维度不同，语义空间也完全不同——不能混在一个索引里检索。要同时支持，就得维护两份知识库、两套索引、两条评测基线，而"当前用的是哪套"这个隐性状态会渗进缓存、评测和线上数据。

代价是联网、单次 200-500ms。靠两条抵消：精确药名命中标题时直接走 BM25 不算向量（0.22s），以及查询级 embedding 缓存。

**不配 `VOLC_ARK_API_KEY` 时**向量检索关闭，`/health` 显示 `vector: false`，检索降级为 BM25 + 重排。功能还在，只是召回差一档——和持久化不同，这里不丢数据，所以允许降级。

索引带数据指纹：种子数据改了会自动重建，不需要手动删索引。

**离线模式**：`HA_LLM_OFFLINE=true` 时不发真实请求，用固定逻辑跑通链路——红旗中断、意图漏斗、Safety 审查这三条都能验证。
只认这个开关，不再按"有没有密钥"推断：拆出网关后 core 本来就没有密钥，按密钥判断会让它静默走桩。

## 启动

```bash
# 模型网关（先起，core 依赖它）
java -jar llm-gateway/target/llm-gateway-0.1.0-SNAPSHOT.jar --server.port=8081

# 后端（注意显式指定端口：部分宿主环境会注入 SERVER_PORT 覆盖配置）
java -jar health-agent/target/health-agent-0.1.0-SNAPSHOT.jar --server.port=8080

# 或从源码跑
mvn -pl llm-gateway spring-boot:run
mvn -pl health-agent spring-boot:run

# 前端
cd web && npm run dev

# 知识服务
cd knowledge-service && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8001
```

打包：`mvn -DskipTests package`，产物在 `health-agent/target/` 与 `llm-gateway/target/`。

## 评测

```bash
curl -X POST localhost:8080/api/v1/evaluation/redflag     # 50 条用例，纯规则，秒级
curl -X POST localhost:8080/api/v1/evaluation/factcheck   # 调模型生成回答再验证引用，约 20 秒
curl -X POST localhost:8080/api/v1/evaluation/otc         # 正向问药是否越界推荐处方药，约 40 秒
```

当前成绩：**红旗 50/50（100%）**、**事实校验 8/8（100%）**、**OTC 合规 8/8（100%）**。

红旗评测从 82% 提到 100% 靠三件事：修 `age` 未知时儿童规则被整体关掉的逻辑错误、补同义词（"脸色发白"/"虚汗"/"嘴唇发紫"）、加正则支持（"吐了一口血"这类词序变体靠枚举永远补不完）。

**OTC 合规**抓的是"编了一个真实但越界的药"——事实校验只能抓"编了一个不存在的药"。做法是抽推荐语境里的药名（句子含"建议/推荐/首选"且不含否定词），回查知识库 `otc` 字段，非 OTC 即失败。这条评测只有放开"可推荐具体 OTC"之后才有意义。

**刻意不做 LLM 互评**：用模型判模型，测的是两个模型的一致性，不是正确性。

## 状态

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 骨架 | ✅ | 模块化单体、ArchUnit 架构约束、trace |
| M1 知识层 | ✅ | ES 检索（cjk_bigram 分词）+ Neo4j 图谱，连不上自动回退内存 |
| M2 内核 + 路由 | ✅ | 红旗 + 域外过滤 + Manager 单一路由，五个 execute 能力 |
| M3 报告解读 | ✅ | 文本 + 图片 OCR（RapidOCR 本地跑），逐项补出处 |
| M4 问药 | ✅ | 逆向查询给说明书；正向可推荐**具体 OTC 品种**（五项必给：通用名+剂型 / 成人用法 / 疗程上限 / 停药指征 / 关键禁忌） |
| M5 记忆 | ✅ | append-only 账本 + 投影 + dreaming 抽取，跨会话生效 |
| M6 评测 | ✅ | 红旗 + 事实校验 + OTC 合规三档 |
| M7 前端 | ✅ | 对话 / 报告 / 评测三个视图，支持图片上传与位置设置 |
| M8 持久化 | ✅ | MySQL 8.0：会话、消息、账本、投影、矛盾；重启不丢，**连不上直接启动失败**（不降级） |
| M9 重排 | ✅ | bge-reranker-base（ONNX），只提升正分候选，失败静默降级 |
| M11 路由重构 | ✅ | 删 L1 业务规则与 L2 向量层（实测增量≈0），规则只拦域外，Manager 唯一路由者 |
| M12 单 agent + 四工具 | ✅ | consult/pharma 合并成 doctor；`drug_info`（结构化说明书）/ `health_record`（本人档案）/ `find_care`（科室+地点）/ `knowledge_search` |
| M13 药品库与召回 | ✅ | 药品 11 → 78 味（35 OTC + 43 处方药），数据移出代码；加 `aliases` 承接口语词；匹配下限从 `70%` 改 `1<2`（百分比会滤掉多实体长问句）；重排分全体 < -5 返回空 |
| M14 双层数据源 | ✅ | 人工整理 35 味（中文）+ openFDA 导入 43 味（英文原文，公有领域 CC0）；`scripts/import_openfda.py` 可重跑；`scripts/import_otc_labels.py` 解析 NMPA 说明书范本（待网络可达） |
| M15 五 agent 拆分 | ✅ | doctor / pharma / report / care / wellness，**每个 agent 带一件别人没有的工具**；新增抽样评测 `/api/v1/evaluation/sample`；记忆拆健康档案层与习惯层 |
| M16 清理 | ✅ | 删除从未接入的卡片/假设子系统、`consult`/`place` 两个死 prompt、`specialtyFragment` 空参数、`disease_catalog.yaml`；修正内核 JSON 契约的旧 target 列表 |
| M10 dreaming | ✅ | 档案页聚合（页间互链）+ 矛盾检测（过敏翻转/剂量变化/停药再开）+ 待确认注入画像 |
| 附加 | ✅ | 附近药店 POI（高德）、多租户（`X-Tenant-Id`）、ES 向量检索（doubao 2048 维 + RRF 融合） |

## 记忆相关接口

```bash
curl "localhost:8080/api/v1/memory/profile?userId=u1"            # 档案页（含互链、记录次数、最近证据）
curl "localhost:8080/api/v1/memory/contradictions?userId=u1"     # 待确认项
curl -X POST "localhost:8080/api/v1/memory/contradictions/5/resolve?status=RESOLVED"
curl "localhost:8080/api/v1/memory/stats"                        # 账本条数、矛盾数
curl "localhost:8080/api/v1/sessions/{id}"                       # 会话历史（刷新/重启后恢复）
```

**未做**：ES 的 rerank 训练/微调、档案页的人工编辑、多租户配额隔离。

## 测试数据与脱敏

`docs/test-data/` 下是 5000 条评测语料，用于路由准确率与安全闸门的回归测试：

| 来源 | 条数 | 说明 |
|---|---|---|
| 公开医患问答 | 4284 | 公开问诊平台上用户自行发布的提问，仅保留问题文本 |
| MedSafetyBench | 60 | 公开医疗安全基准 |
| JailBench | 20 | 公开越狱鲁棒性基准 |
| 人工构造 | 其余 | 域外、多意图、边界场景 |

**已做的脱敏**：原始语料里夹带的用户联系方式（邮箱、QQ、微信、电话）和第三方机构推广网址，全部替换为 `（已脱敏）` 占位符。语料本身不含姓名、身份证号、病历号等直接标识符。

**未做的事**：没有逐条回溯语料原始出处并取得授权。这些文本是用户主动发布在公开渠道的提问，此处仅作技术评测用途聚合。如果你是要在商业场景复用，请自行确认合规性。

## 免责声明

- 本项目是**工程技术演示**，不是医疗器械，未取得任何医疗器械注册证，**不提供诊断、处方或治疗建议**。
- 仓库内的所有回答、药品信息、参考范围仅用于验证工程链路，**不能替代执业医师的面诊**。身体不适请就医。
- 文档中的"小荷 AI 医生"对照，依据是公开可下载 App 的界面结构与公开资料，**不包含其源代码或私有数据**，仅作产品能力的技术分析。
- 药品数据来源：人工整理的中文 OTC 条目 + openFDA（公有领域 CC0）。引用公开数据集的部分遵循其各自许可。

## License

[MIT](LICENSE) © 2026 Fenglin6666
