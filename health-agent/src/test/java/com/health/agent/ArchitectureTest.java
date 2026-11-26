package com.health.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构约束。这些规则不是文档，是测试——违反就构建失败。
 *
 * <p>对应开发规范 R1 / R2 / R3。
 */
@AnalyzeClasses(packages = "com.health.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** R1：内核是哑的，不知道任何业务包存在。 */
    @ArchTest
    static final ArchRule kernel_is_dumb = noClasses()
            .that().resideInAPackage("..kernel..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..manager..", "..execute..", "..pharma..",
                    "..report..", "..safety..", "..memory..", "..knowledge..",
                    "..integration..", "..evaluation..",
                    "..routing..", "..platform..");

    /**
     * R1：下层不依赖上层。
     *
     * <p>原来的写法是 {@code slices().matching("com.health.agent.(manager|execute)..")}——
     * 但 {@code manager} 不是包（它是 ExecuteAgents 里的一个名字），切片只剩 execute 一个，
     * {@code notDependOnEachOther} 对单切片恒真。<b>这条规则从写下起就没拦过任何东西。</b>
     *
     * <p>改成约束真正的分层边界：能力层与适配层只能往下看，不能依赖接入层、持久化、编排。
     * 允许依赖 {@code platform.config}（PromptLoader 这类横切配置），它不属于上层组件。
     */
    @ArchTest
    static final ArchRule lower_layers_do_not_depend_on_upper = noClasses()
            .that().resideInAnyPackage(
                    "..safety..", "..memory..", "..report..",
                    "..knowledge..", "..integration..", "..llm..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..platform.api..", "..platform.persistence..",
                    "..execute..", "..evaluation..");

    /**
     * R2：外部 HTTP 只有三个出口——知识服务、模型 API、第三方集成。
     *
     * <p>{@code evaluation} 是例外：它调的是<b>本服务自己</b>（评测必须走真实 HTTP 链路，
     * 否则测不出 SSE 生命周期和会话落库）。这条规则防的是业务代码乱发外部请求，
     * 评测工具不在此列。
     */
    @ArchTest
    static final ArchRule only_three_http_exits = noClasses()
            .that().resideOutsideOfPackages("..knowledge..", "..llm..", "..integration..", "..evaluation..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("java.net.http..", "okhttp3..", "org.apache.http..");
}
