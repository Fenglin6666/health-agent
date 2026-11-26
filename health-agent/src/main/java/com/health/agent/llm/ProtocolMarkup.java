package com.health.agent.llm;

import java.util.regex.Pattern;

/**
 * 识别模型把内部工具调用协议漏进正文的情况。
 *
 * <p>DeepSeek-V4 有原生的 DSML 标记（{@code <｜｜DSML｜｜tool_calls>}）。本项目不走 API
 * 原生 tools 参数，但 prompt 里有工具清单，模型仍会按 tokenizer 的固有模式吐出来。
 * 实测形态会退化：带空格、半角竖线，例如 {@code < || DSML || parameter>}。
 *
 * <p>靠 prompt 抑制无效——DSML 是 tokenizer 层面的模式，不是指令能关的。所以只能在
 * 出口识别：识别到就说明这次响应不是"回答"，而是"没走通的工具调用"，不能当文本发给用户。
 */
final class ProtocolMarkup {

    /** 容忍空格、全半角竖线、单双竖线：实测退化形态比标准格式多得多。 */
    private static final Pattern DSML = Pattern.compile(
            "<\\s*/?\\s*[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}",
            Pattern.CASE_INSENSITIVE);

    private ProtocolMarkup() {
    }

    static boolean present(CharSequence text) {
        return text != null && DSML.matcher(text).find();
    }
}
