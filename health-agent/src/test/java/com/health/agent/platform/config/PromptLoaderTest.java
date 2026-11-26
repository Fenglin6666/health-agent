package com.health.agent.platform.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 片段展开的边界。include 是字符串替换，最怕两件事：
 * 漏展开（用户看到的 prompt 里带 {{include:...}} 字样）和循环引用（栈溢出）。
 */
class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader();

    @Test
    void expandsFragmentIntoDoctorPrompt() {
        String p = loader.load("prompts/execute/doctor.v1.md");
        assertTrue(p.contains("胸痛伴大汗或濒死感"), "红旗清单应被展开");
        assertFalse(p.contains("{{include:"), "不应残留 include 占位符");
    }

    @Test
    void careAlsoGetsRedFlags() {
        String p = loader.load("prompts/execute/care.v1.md");
        assertTrue(p.contains("危险信号"), "care 也要能识别红旗");
        assertFalse(p.contains("{{include:"));
    }

    @Test
    void pharmaAlsoGetsRedFlags() {
        String p = loader.load("prompts/execute/pharma.v1.md");
        assertTrue(p.contains("危险信号"));
        assertFalse(p.contains("{{include:"));
    }

    @Test
    void missingFragmentFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> loader.load("prompts/fragments/does-not-exist.md"));
        assertTrue(e.getMessage().contains("not found"));
    }
}
