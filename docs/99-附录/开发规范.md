# 开发规范 · 防腐指南

> 配套文档：`health-agent-design.md`
> 目标：让代码在 24 周后还能改得动

---

## 0. 先定义"腐坏"

腐坏不是"写得不优雅"，不是"没注释"，也不是"命名不规范"。腐坏只有一个判据：**改不动了**。

具体表现为六个数字变大：

| 变更 | 健康值 | 腐坏信号 |
|---|---|---|
| 加一个 execute agent | 改 2 个文件（配置 + 一个类） | > 4 个 |
| 加一张问诊卡片 | 改 1 个文件（YAML） | > 2 个 |
| 换一个模型供应商 | 改 1 个文件（`llm/` 适配器） | > 2 个 |
| 加一类健康事实 | 改 3 个文件（枚举 + `pages.yaml` + 抽取 prompt），**0 条 SQL** | > 5 个 |
| 给已有表加字段 | 改 3 处（schema + INSERT + RowMapper） | > 5 处 |
| 加一张新表 | 改 4 个文件（schema + 接口 + Jdbc 实现 + 内存实现） | > 6 个 |

**这六条是本文档唯一的验收标准。** 其余条款都是为了守住它们。

**双实现规则（持久化的隐性成本）**：核心路径——会话、账本、矛盾——保留双实现（MySQL + 内存）。但内存版**只服务于显式的 `ha.persistence.mode=memory` 和单元测试**，不再承担"数据库挂了自动降级"的职责——那条路已经堵死，连不上就启动失败。**新功能只做 MySQL 单实现**，memory 模式下明确返回"该功能需要数据库"，不要再对称地写一套内存版。

理由：双实现的成本随表数量线性增长，而它买到的只是"改 prompt 时不用开 Docker"。用 `mode=memory` 一个开关就能买到，不需要每张表都写两遍。

顺带说明账本为什么用 EAV 结构（`owner / fact_type / fact_key / fact_value`）：加一类事实只改枚举，不加表、不写 SQL。"多记点东西"是记忆型产品最常发生的改动，把它做成零成本，比任何框架选型都值。

---

## 1. 个人项目的腐坏路径

团队项目靠 review 防腐，个人项目没有 review，所以腐坏路径不一样。四条最典型的：

1. **"先这样，回头重构"** —— 回头永远不会来。重构需要契机，而契机是"改不动了"，那时候成本已经翻了十倍。
2. **"这个逻辑先写在 controller 里"** —— 半年后 controller 3000 行，你不敢动它。
3. **"规则先复制一份"** —— 三份副本，改一处漏两处，而且你不知道有第三份。
4. **"测试先不写"** —— 改代码全靠手动点，于是不敢改。

对应的四条对策，就是下面五条硬规则 + 五条软约定。

---

## 2. 五条硬规则（构建时自动检查）

**硬规则的定义：违反就构建失败，不靠自觉。**

### R1 依赖方向单向

`kernel` 和 `domain` 不知道任何业务包存在。业务包之间不互相依赖，只通过 `kernel` / `domain` 暴露的接口通信。

```java
@ArchTest
static final ArchRule kernel_is_dumb = noClasses()
    .that().resideInAPackage("..kernel..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("..manager..", "..consult..", "..pharma..",
                        "..report..", "..place..", "..know..",
                        "..safety..", "..memory..", "..knowledge..");

@ArchTest
static final ArchRule business_packages_are_isolated = slices()
    .matching("com.health.agent.(manager|consult|pharma|report|place|know)..")
    .should().notDependOnEachOther();
```

**为什么**：这是唯一能防止"业务逻辑渗透进内核"的机制。一旦 kernel 里出现了 `if (科室 == 心内科)`，整个抽象就废了。

### R2 外部调用只有两个出口

只有 `knowledge/` 能发 HTTP 到知识服务，只有 `llm/` 能发 HTTP 到模型 API。其余任何包都不许碰 `java.net.http` / `okhttp` / `HttpClient`。

```java
@ArchTest
static final ArchRule only_two_exits = noClasses()
    .that().resideOutsideOfPackages("..knowledge..", "..llm..", "..infra..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("java.net.http..", "okhttp3..", "org.apache.http..");
```

**为什么**：出口收敛后，重试、超时、熔断、trace、token 计数只需要在两个地方写。散落各处就是二十个地方各写一遍，且各不相同。

### R3 领域层零框架依赖

`domain` 包里不能出现 Spring、Jackson、JPA 的注解。

```java
@ArchTest
static final ArchRule domain_is_pure = noClasses()
    .that().resideInAPackage("..domain..")
    .should().dependOnClassesThat()
    .resideInAnyPackage("org.springframework..", "com.fasterxml.jackson..",
                        "jakarta.persistence..", "org.hibernate..");
```

**为什么**：domain 是唯一能脱离 Spring 上下文跑单测的部分。一旦被框架污染，测试就要起容器，测试慢了你就不想跑，不想跑就等于没有。

### R4 Prompt 与规则必须外置

Java 代码里不允许出现超过 200 字符的字符串字面量。Prompt 走 `resources/prompts/`，规则表走 `configs/`。

```xml
<!-- checkstyle -->
<module name="RegexpSingleline">
  <property name="format" value="&quot;.{200,}&quot;"/>
  <property name="message" value="长字符串必须外置到资源文件"/>
</module>
```

**为什么**：Prompt 是资产，需要 diff、回滚、版本化、被评测集覆盖。写在 Java 字符串里，这三件事一件都做不了。

### R5 测试门禁

| 模块 | 行覆盖率下限 |
|---|---|
| `domain` | 80% |
| `kernel` | 60% |
| 整体 | 50% |

**为什么**：覆盖率不是目的，是"你敢不敢改"的代理指标。domain 和 kernel 是纯逻辑，不测没理由。

---

## 3. 五条软约定（无法自动检查，但很重要）

软约定不写进 CI，但每次里程碑结束自查一遍。

### S1 命名表达意图，不用万能词

禁止 `Utils` / `Helper` / `Manager` / `Processor` / `Handler` 这类词做类名后缀——它们什么都没说。

- ❌ `CardUtils.build()`
- ✅ `CardSelector.pick(hypotheses)`

**判断方法**：如果类名去掉后缀就看不懂它是干什么的，说明名字起错了。

### S2 一个类一个职责，超 400 行先想拆

不是硬限制（业务复杂的类可以长），但超过 400 行要问自己一句：这里是不是混了两件事？

**腐坏信号**：类的字段超过 10 个，且它们不总是一起被使用。

### S3 注释写"为什么"，不写"是什么"

```java
// ❌ 遍历所有假设
for (Hypothesis h : hypotheses) { ... }

// ✅ 只保留前 5 个假设：卡片选择一次最多对比 5 个，多了信息增益计算会退化成噪声
for (Hypothesis h : topN(hypotheses, 5)) { ... }
```

**为什么**：代码已经说了"是什么"，注释再说一遍是噪音。只有"为什么这么做"和"为什么不能那么做"值得写。

### S4 不吞异常

```java
// ❌
try { ... } catch (Exception e) { }

// ✅
try { ... } catch (ToolTimeoutException e) {
    log.warn("tool timeout, degrade to fallback: {}", e.getMessage());
    return ToolResult.degraded(e);
}
```

**为什么**：吞掉的异常会以"莫名其妙的结果错了"的形式在两周后回来，而那时你已经不记得这里有个 catch。

### S5 一次提交只做一件事

改 prompt 和改架构不要放一个 commit。**理由是回滚**：混在一起就回滚不了，只能整体回退。

### S6 配置前缀必须加命名空间

```yaml
# ❌ 容易被宿主环境覆盖：IDE/容器/CI 常注入 LLM_API_KEY
llm:
  api-key: ${LLM_API_KEY:}

# ✅ 加一层前缀，环境变量是 HA_LLM_APIKEY，撞不上
ha:
  llm:
    api-key: ${HA_LLM_API_KEY:}
```

**为什么**：Spring 的 relaxed binding 会把环境变量 `LLM_API_KEY` 直接映射到 `llm.api-key`，而且**优先级高于 yml**、**不报错**。这类覆盖是静默的，表现是"配置明明写了却不生效"，排查成本极高。

这条不是理论——本项目实测踩过：宿主环境注入了 `LLM_API_KEY`，导致离线模式失效、请求超时。

---

## 4. 腐坏检查清单（每 4 周跑一次）

不是看代码，是问问题。每个问题都能用一分钟回答：

1. 有没有包绕过 `Repository` 直接拼 SQL？
2. 同一条医学规则有没有出现在两个以上文件里？
3. 有没有 prompt 还写在 Java 字符串里？
4. 最大的类多少行？（> 500 要处理）
5. 有没有超过 2 周没动的 TODO？
6. **加一个新科室，要改几个文件？**（> 3 = 腐坏）
7. **换一个模型，要改几个文件？**（> 2 = 腐坏）
8. 上一次跑评测是什么时候？（> 2 周 = 不知道现在什么水平）

第 6、7 条是核心。它们测的不是代码美观，是**架构的可扩展性**。

---

## 5. 工具配置

### 5.1 Maven 依赖

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

### 5.2 静态检查

| 工具 | 管什么 | 阈值 |
|---|---|---|
| Checkstyle | 长字符串、`System.out`、空 catch | 违规即失败 |
| PMD | 圈复杂度、重复代码 | 复杂度 ≤ 15，重复块 ≥ 100 行告警 |
| SpotBugs | 空指针、资源泄漏、错误用法 | High 级别即失败 |
| JaCoCo | 覆盖率 | 见 R5 |

**原则：只开会产生"必须修"的规则。** 开了不修的告警等于没开，还会训练你忽略告警。

### 5.3 前端（Vue + TS）

| 规则 | 说明 |
|---|---|
| `vue-tsc --noEmit` | 类型检查必须过 |
| API 调用只走 `src/api/` | 组件里不许直接 `fetch` |
| Pinia store 不放业务逻辑 | 只存状态和派生值，业务在 `src/domain/` |
| 组件超 300 行拆分 | 同上，超了先想职责 |

---

## 6. 架构决策的记录方式

改架构前先改 ADR，不要在代码里悄悄改。

**判断标准**：如果一个改动会让"三个变更成本指标"里的任何一个变大，它就是架构改动，需要 ADR。

ADR 模板（沿用设计文档的格式）：

```markdown
### ADR-XX 标题
**决策**：选了什么
**理由**：为什么
**代价**：放弃了什么
**放弃的方案**：考虑过但没选的，以及为什么
```

**为什么要写"放弃的方案"**：三个月后你会忘记为什么没选另一条路，然后重新走一遍弯路。

---

## 7. 一句话总结

**规范的价值不在"写了什么"，在"能不能自动检查"。**

不能自动检查的规范，三个月后一定失效。所以：

- 能用代码断言的（依赖方向、出口收敛、覆盖率）→ 写成测试
- 不能用代码断言的（命名、注释、提交粒度）→ 靠结构和习惯，且只保留五条
- 剩下的（行数、复杂度）→ 设阈值，超了人工判断

**最有效的防腐不是规范条文，是三个结构约束**：依赖单向、出口唯一、配置外置。这三条守住了，代码很难烂到改不动。
