package com.oac.nazhiyazi.op;

/**
 * AI 模型配置项。一个模型 = 一组 API地址 + 模型ID + Key + 采样参数。
 * 兼容 OpenAI / DeepSeek / Moonshot (Kimi) / OpenRouter 等任何 OpenAI 兼容协议。
 */
public class ModelConfig {
    public String id;            // 内部唯一ID（用时间戳生成）
    public String name;          // 显示名称，如 DeepSeek / Kimi
    public String apiUrl;        // 完整 chat completions URL
    public String modelId;       // 模型 ID，如 deepseek-chat
    public String apiKey;        // API Key
    public double temperature;   // 0.0 ~ 2.0
    public int maxTokens;        // 0 表示不限制
    public String systemPrompt;  // 系统提示词，可为空

    /**
     * 思考模式：
     * 0 = 默认（API 返回 reasoning_content 就显示，不额外发参数）
     * 1 = 开启思考（请求带 include_reasoning=true，有思考内容就显示）
     * 2 = 不思考（即使 API 返回思考内容也不显示）
     */
    public int thinkingMode;

    /** 是否支持多模态（视觉）：开启后可在消息中附加图片 */
    public boolean multimodal;

    /** 是否开启联网搜索（Tool Calls）：开启后请求附带 tools 定义，模型可调用 web_search */
    public boolean enableToolCalls;

    /**
     * 模型优化模式：
     * 0 = 默认（通用 OpenAI 兼容请求）
     * 1 = DeepSeek（发 include_reasoning 等 DeepSeek 专用参数）
     * 2 = Google AI（发 Gemini / Google 兼容专用参数）
     */
    public int optimizationMode;

    public static final int THINK_DEFAULT = 0;
    public static final int THINK_ON = 1;
    public static final int THINK_OFF = 2;

    public static final int OPT_DEFAULT = 0;
    public static final int OPT_DEEPSEEK = 1;
    public static final int OPT_GOOGLE = 2;

    public ModelConfig() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.name = "";
        this.apiUrl = "";
        this.modelId = "";
        this.apiKey = "";
        this.temperature = 0.7;
        this.maxTokens = 2048;
        this.systemPrompt = "";
        this.thinkingMode = THINK_DEFAULT;
        this.multimodal = false;
        this.enableToolCalls = false;
        this.optimizationMode = OPT_DEFAULT;
    }

    public boolean isDefaultOptimized() {
        return optimizationMode == OPT_DEFAULT;
    }

    public boolean isDeepSeekOptimized() {
        return optimizationMode == OPT_DEEPSEEK;
    }

    public boolean isGoogleOptimized() {
        return optimizationMode == OPT_GOOGLE;
    }

    /**
     * 是否应请求/显示思考内容。
     * - THINK_OFF：不请求也不显示
     * - THINK_ON：强制请求并显示（用户显式开启）
     * - THINK_DEFAULT：
     *   · 用户显式选了 DeepSeek / Google 优化模式时，直接按该模式请求思考
     *   · 默认优化模式下，按模型身份（URL/modelId）自动判断
     */
    public boolean shouldShowReasoning() {
        if (thinkingMode == THINK_OFF) return false;
        if (thinkingMode == THINK_ON) return true;
        // THINK_DEFAULT
        if (isDeepSeekOptimized()) return true;
        if (isGoogleOptimized()) return true;
        return isLikelyDeepSeek() || isLikelyGoogle(); // 默认自动识别 DeepSeek / Google
    }

    public boolean forceRequestReasoning() {
        return thinkingMode == THINK_ON;
    }

    /** 根据 URL / modelId 判断是否为 DeepSeek */
    public boolean isLikelyDeepSeek() {
        if (apiUrl != null) {
            String url = apiUrl.toLowerCase();
            if (url.contains("deepseek")) return true;
        }
        if (modelId != null) {
            String model = modelId.toLowerCase();
            if (model.contains("deepseek")) return true;
        }
        return false;
    }

    /** 根据 URL / modelId 判断是否为 Google / Gemini */
    public boolean isLikelyGoogle() {
        if (apiUrl != null) {
            String url = apiUrl.toLowerCase();
            if (url.contains("google") || url.contains("gemini")) return true;
        }
        if (modelId != null) {
            String model = modelId.toLowerCase();
            if (model.contains("google") || model.contains("gemini")) return true;
        }
        return false;
    }
}
