# 启动器说明

这套脚本只新增文件，没有改动任何项目源码。删掉 `start.bat`、`stop.bat`、`_launch/` 就回到原样。

---

## 一、探测结论

| 项 | 结果 |
|---|---|
| 技术栈 | Java 21（Spring Boot 3.3.5）+ Python（FastAPI）+ Vue 3（Vite） |
| 构建 | Maven 多模块：`health-agent`、`llm-gateway` |
| 启动入口 | 两个可执行 jar + uvicorn + vite |
| 端口 | core 8080、llm-gateway 8081、knowledge 8001、web 5173 |
| 必需外部依赖 | MySQL `127.0.0.1:3307`（core 连不上直接启动失败，不降级） |
| 可选外部依赖 | Elasticsearch `:9200`、Neo4j `:7474`（知识服务用，缺了降级） |
| JDK 位置 | 不在 PATH，脚本会自动扫 `%USERPROFILE%\.jdks\*` |
| 模型密钥 | 只在 llm-gateway 进程里；core 不持有 |

---

## 二、脚本清单

| 文件 | 作用 |
|---|---|
| `start.bat` | 双击启动全部；先检查 Java / jar / MySQL / 端口，再按顺序拉起 |
| `stop.bat` | 按端口杀进程（8080 / 8081 / 8001 / 5173） |
| `_launch\env.bat` | 解析 `java.exe`：先找 PATH，再找 `%USERPROFILE%\.jdks\*` |
| `_launch\run-gateway.bat` | 启动 llm-gateway（读密钥） |
| `_launch\run-core.bat` | 启动 core（清空 `HA_LLM_BASE_URL` / `HA_LLM_API_KEY`） |
| `_launch\run-knowledge.bat` | 启动 uvicorn |
| `_launch\run-web.bat` | 启动 vite，`node_modules` 缺失时自动 `npm install` |
| `_launch\llm-key.txt.example` | 密钥文件模板 |

---

## 三、密钥怎么给

三种方式，优先级从高到低：

1. 环境变量 `HA_LLM_API_KEY`（已设置就用它）
2. `_launch\llm-key.txt`，一行，只有 key，不要引号不要空格
3. 都没有 → **离线模式**：不启网关，core 用固定逻辑跑通链路，回答是模板文案

离线模式下红旗中断、意图路由、Safety 审查都能验证，只是内容不是模型生成的。

---

## 四、两个必须知道的坑

### 1. `HA_LLM_BASE_URL` 会绕过网关

环境里如果还留着 `HA_LLM_BASE_URL=https://api.deepseek.com/v1`，
Spring 会用它覆盖配置文件，core 直接连上游——而 core 没有密钥，结果是 401。

`start.bat` 第一行就 `set "HA_LLM_BASE_URL="`，`run-core.bat` 里又清了一次。
如果你绕过脚本手工启动，务必自己 unset。

### 2. 端口被占用时不会自动杀进程

检测到 8080/8081 被占，脚本会提示先跑 `stop.bat`，不会替你杀。
这样避免误杀别的项目。

---

## 五、首次启动会做什么

- 检查 Java、两个 jar、MySQL、端口
- 没有 `_launch\llm-key.txt` 且没有环境变量 → 进离线模式
- 起网关（在线模式），等 `/health` 返回 200，最多 40 秒
- 起 core，等 `/api/v1/routing/stats` 返回 200，最多 90 秒
- 起知识服务和前端（存在才起，缺了只警告不阻断）
- 延迟 6 秒打开浏览器

之后启动跳过所有检查里的等待，通常 10 秒内全部就绪。

---

## 六、手工命令（脚本坏了时的退路）

```bash
# 网关
export HA_LLM_API_KEY=sk-xxx
java -jar llm-gateway/target/llm-gateway-0.1.0-SNAPSHOT.jar --server.port=8081

# core（必须先清掉两个变量）
unset HA_LLM_BASE_URL HA_LLM_API_KEY
java -jar health-agent/target/health-agent-0.1.0-SNAPSHOT.jar --server.port=8080

# 知识服务
cd knowledge-service && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8001

# 前端
cd web && npm run dev
```

---

## 七、验证记录（2026-11-26）

实际跑过一遍，不是只写不测：

| 检查 | 结果 |
|---|---|
| `env.bat` 解析 JDK | ✅ 找到 `%USERPROFILE%\.jdks\ms-21.0.12.1\bin\java.exe` |
| 端口占用检测 | ✅ 8080 占用时退出 0，9999 空闲时退出 1 |
| 缺 jar / MySQL 不可达 | ✅ 有明确报错并停在 pause |
| 完整启动 | ✅ gateway / core 健康检查通过，knowledge / web 窗口拉起 |
| `stop.bat` | ✅ 8080 / 8081 显示 stopped，8001 / 5173 显示 idle |

一个和脚本无关的现象：如果从**脚本宿主**（CI、IDE 的任务、自动化工具）里调用 `start.bat`，
宿主退出时可能把整棵进程树一起收掉，看起来像"启动完就没了"。
从资源管理器双击没有这个问题——子窗口的父进程是 explorer。

---

## 八、要不要提交

`_launch\llm-key.txt` **绝对不要提交**。如果这个目录以后进 git，建议加：

```
_launch/llm-key.txt
```

`start.bat` / `stop.bat` / `_launch/` 其余文件可以提交，团队共用一套启动方式。
