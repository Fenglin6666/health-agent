package com.health.agent.execute;

import com.health.agent.kernel.api.AgentConfig;
import com.health.agent.kernel.api.KernelResult;
import com.health.agent.kernel.app.AgentKernel;
import com.health.agent.kernel.domain.Session;
import com.health.agent.llm.LlmProperties;
import com.health.agent.platform.config.AgentProperties;
import com.health.agent.platform.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 所有 execute agent 的统一入口。
 *
 * <p>这里体现了"单内核多配置"：五个能力不是五个类，而是五份 prompt + 五组工具。
 * 加一个能力 = 加一个 prompt 文件，不改内核、不改路由。
 *
 * <p>约定：prompt 路径固定为 {@code prompts/execute/{target}.v1.md}，
 * 文件名就是 target，省掉一层映射配置。
 */
@Component
public class ExecuteAgents {

    private static final Logger log = LoggerFactory.getLogger(ExecuteAgents.class);

    /**
     * target → 工具清单。**这张表就是"系统有哪几个 agent"的唯一来源。**
     *
     * <p>原来是 switch，加 agent 要改 switch、加 prompt 文件、再改 application.yml 的
     * max-steps，三处不同步就是一次静默降级。现在改成一张表：启动时遍历它的 key
     * 去校验 prompt 是否存在，加 agent 漏了 prompt 会在**启动时**炸，而不是等用户问到。
     *
     * <p>每个 agent 必须带一件别人没有的工具，否则它只是"另一段 prompt + 同一批工具"，
     * 带来的只有一次额外路由判断：
     * <ul>
     *   <li>doctor —— knowledge_search：疾病、检查、指南（唯一有医学知识库的）</li>
     *   <li>pharma —— web_search：说明书修订、召回、集采这类"现在/国内"的时效信息</li>
     *   <li>report —— lab_reference：参考范围与箭头含义（模型记不准，必须查表）</li>
     *   <li>care —— find_care：科室建议 + 附近 POI</li>
     *   <li>wellness —— daily_context：日期/节气/天气</li>
     * </ul>
     *
     * <p><b>药品信息是 pharma 独占的</b>。doctor 不挂 drug_info——职责一重叠，交接就永远不会发生：
     * 本地库没命中时 doctor 会卡在原地停住，而不是把问题转给有联网能力的 pharma。
     * 宁可多一次交接往返，也不要一个答不上来还不肯撒手的 agent。
     */
    private static final Map<String, List<String>> TOOLS = Map.of(
            // manager 是链上的第一个节点：不绑工具，只做一次判断然后交出控制权。
            // 把它放进同一张表，是为了让"目标是否存在"只有一套校验。
            "manager", List.of(),
            // doctor 只拿医学知识库。药品信息是 pharma 的独占职责——
            // 给 doctor 挂 drug_info 会让它在本地库没命中时直接卡住停住，
            // 而不是交接出去；职责一重叠，交接就永远不会发生。
            // daily_context（日期/节气/天气）给 doctor：护理建议离不开"现在什么季节、今天冷不冷"。
            // 原来它属于 wellness，但 wellness 除了这个工具没有任何独占能力，
            // 留着只会多一条路由边界、多一份要维护的 prompt。
            "doctor", List.of("knowledge_search", "health_record", "daily_context"),
            "pharma", List.of("drug_info", "web_search", "health_record"),
            "report", List.of("lab_reference", "knowledge_search", "health_record"),
            "care", List.of("find_care")
    );

    /**
     * target → 一句话职责。和 {@link #TOOLS} 一一对应，只在这里写一次。
     *
     * <p>为什么要单独一份职责描述：交接判断靠的是"这件事该谁做"，只给目标名（care/pharma）
     * 模型不一定能对上——用户说"附近哪里有药店"，它可能觉得跟药有关就自己答了。
     * 把职责写清楚，判断才有依据。
     */
    private static final Map<String, String> ROLE = Map.of(
            "manager", "入口分流，判断该交给谁",
            "doctor", "症状与疾病判断、护理与作息建议、要不要就医",
            "pharma", "具体药品的用法、副作用、相互作用",
            "report", "化验单与检查指标解读",
            "care", "挂什么科、附近医院和药店、去哪看");

    private final AgentKernel kernel;
    private final PromptLoader prompts;
    private final String model;
    private final String managerModel;
    private final AgentProperties agentProps;

    public ExecuteAgents(AgentKernel kernel, PromptLoader prompts, LlmProperties props, AgentProperties agentProps) {
        this.kernel = kernel;
        this.prompts = prompts;
        this.model = props.modelExecute();
        this.managerModel = props.modelManager();
        this.agentProps = agentProps;
    }

    /**
     * 启动自检：每个 target 都必须有对应的提示词。
     *
     * <p><b>把"运行时兜底"换成"启动即失败"</b>。原来的做法是 prompt 缺失时静默退到
     * 一份通用科普 prompt——用户问的是问诊，拿到的是科普口吻，而且没人会发现（只有一行 WARN）。
     * 配置错误降级成"能跑但答错"，比启动失败危险得多。
     *
     * <p>这也让下面 {@link #configFor} 里的异常在实践中不可达：真缺了，进程根本起不来。
     */
    @jakarta.annotation.PostConstruct
    void verifyPrompts() {
        for (String target : TOOLS.keySet()) {
            String path = promptPath(target);
            try {
                prompts.load(path);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        "agent '" + target + "' 缺少提示词 " + path
                                + "。加 agent 必须同时加 prompt，不允许运行时兜底。", e);
            }
        }
        log.info("agent 注册表就绪：{}", TOOLS.keySet());
    }

    /** 目标是否存在。交接前必须先问这一句，不要让不存在的 target 走进内核。 */
    public boolean has(String target) {
        return target != null && TOOLS.containsKey(target);
    }

    /** 全部可用目标，排序后返回。错误提示里要给模型看。 */
    public java.util.List<String> targets() {
        return TOOLS.keySet().stream().sorted().toList();
    }

    public KernelResult run(String target, Session session, String digest) {
        return run(target, session, digest, token -> { });
    }

    /** 带流式出口：回答边生成边交给调用方写 SSE。 */
    public KernelResult run(String target, Session session, String digest, Consumer<String> onToken) {
        AgentConfig config = configFor(target, session, digest);
        session.activeAgent(target);
        return kernel.run(config, session, onToken);
    }

    /** 带预算与步骤回调。编排层用它，预算跨 agent 共享。 */
    public KernelResult run(String target, Session session, String digest, Consumer<String> onToken,
                            com.health.agent.kernel.api.RunBudget budget,
                            com.health.agent.kernel.infra.KernelListener listener) {
        AgentConfig config = configFor(target, session, digest);
        session.activeAgent(target);
        return kernel.run(config, session, onToken, budget, listener);
    }

    private AgentConfig configFor(String target, Session session, String digest) {
        List<String> tools = TOOLS.get(target);
        if (tools == null) {
            // 不猜、不兜底。走到这里说明 Manager 吐了一个不在表里的 target，
            // 而它给的任何"默认能力"都会让这个错误变成一次看起来正常的回答。
            throw new IllegalStateException(
                    "未知 target '" + target + "'，可用的有 " + TOOLS.keySet()
                            + "。检查 Manager 的路由输出与 TOOLS 是否一致。");
        }

        String system = PromptLoader.fill(prompts.load(promptPath(target)), Map.of(
                "digest", digest == null ? "" : digest,
                "location", formatLocation(session),
                "turn_hint", turnHint(session),
                // 可交接的目标从注册表来，不在每份 prompt 里各写一遍——
                // 写两处就会漂移，而漂移的后果是模型交接给一个不存在的 agent
                // 把自己从候选里去掉。模型看不到"自己"这个选项，就不会出现 report→report
                // 这种自交接——那会白烧一步预算，事件流里也看不出发生了什么。
                // 从源头去掉比事后在编排层拦截更干净：拦截只是把错误藏起来。
                "handoff_targets", targets().stream()
                        .filter(t -> !t.equals(target))
                        .map(t -> "- `" + t + "`：" + ROLE.getOrDefault(t, ""))
                        .collect(java.util.stream.Collectors.joining("\n"))
        ));

        // 路由是分类任务，用快模型；执行用强模型。之前这条区分写在 ManagerAgent 里，
        // 合并进同一张表后由这里判断，避免两个地方各配一套。
        String chosen = "manager".equals(target) ? managerModel : model;
        return AgentConfig.of(target, system, chosen)
                .withTools(tools)
                // 单轮任务的步数上限，按 target 从 ha.agent.max-steps 读。
                // 注意这不是"会话轮次"——会话不限轮次，这里限的是一次输入内部的循环次数
                .withMaxSteps(agentProps.maxStepsFor(target))
                // 时间护栏。不是延迟目标——多步检索本来就慢，用 6 秒硬杀只会让用户看到 "timeout"
                .withDeadlineMs(agentProps.deadlineMs());
    }

    private static String promptPath(String target) {
        // manager 的提示词在 prompts/manager/ 下，其余在 prompts/execute/ 下。
        // 这是唯一一处路径例外，写在一处，别在调用点各写一遍。
        return "manager".equals(target)
                ? "prompts/manager/system.v1.md"
                : "prompts/execute/" + target + ".v1.md";
    }

    /**
     * 首轮要求。
     *
     * <p>写在 prompt 里的通用规则模型会"看情况执行"——实测"先给方向再追问"这条规则
     * 连着两版都没被遵守，模型对"信息太少"有自己的临床判断。但**本轮要求**这种
     * 针对当前这一条消息的指令，模型几乎不会忽略。
     *
     * <p>这是 prompt 工程的一个经验：**越接近"这一次要做什么"，越有效**。
     */
    private static String turnHint(Session session) {
        if (session.turnCount() > 1) {
            return "";
        }
        return """
                ## 本轮要求（这是用户的第一句话，信息很少）

                回答必须包含这四部分，顺序不限但都要有：
                1. 现在最可能的方向（哪怕只能说出"最常见的几类是……"）
                2. 要不要用药，或者为什么现在先不用
                3. 什么情况必须就医（具体红线）
                4. 一个问题

                只问一句、什么都不给，是最差的回答方式。
                """;
    }

    /**
     * 把会话里的坐标渲染成 prompt 片段。
     *
     * <p>位置从会话上下文来，不让模型自己问——用户设过一次就不该再问第二遍。
     */
    private static String formatLocation(Session session) {
        Object lat = session.attributes().get("lat");
        Object lng = session.attributes().get("lng");
        if (lat == null || lng == null) {
            return "";
        }
        return "纬度 " + lat + "，经度 " + lng + "（高德坐标系 GCJ-02）。可直接查询附近地点。";
    }
}
