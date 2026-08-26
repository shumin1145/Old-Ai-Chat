package com.oac.nazhiyazi.op;

/**
 * 流式响应中一个 delta 的解析结果。
 * - content: 正常回复内容
 * - reasoning: 思考内容（DeepSeek reasoning_content / OpenAI o1 reasoning）
 * - isToolCall / toolCallId / toolCallName / toolCallArgs: Tool Calls 参数
 */
public class StreamDelta {
    public String content = "";
    public String reasoning = "";

    /** 这条 delta 是否包含 tool_calls */
    public boolean isToolCall;
    /** tool_call id（仅第一个 chunk 携带） */
    public String toolCallId;
    /** function 名称（如 web_search） */
    public String toolCallName;
    /** function arguments 的 JSON 片段（逐 chunk 追加） */
    public String toolCallArgs;

    public boolean hasContent() {
        return content != null && content.length() > 0;
    }

    public boolean hasReasoning() {
        return reasoning != null && reasoning.length() > 0;
    }

    public boolean hasToolCall() {
        return isToolCall;
    }

    public boolean isEmpty() {
        return !hasContent() && !hasReasoning() && !hasToolCall();
    }
}
