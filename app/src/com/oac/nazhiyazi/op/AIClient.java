package com.oac.nazhiyazi.op;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * AI HTTP 客户端。兼容 Android 2.1 (API 7+)。
 *
 * - 通过 NetWorkerFactory 选择网络层：
 *     · OkHttp 2.x + SpongyCastle（Android 2.3+，性能/流式最佳）
 *     · HttpsURLConnection + SpongyCastle 纯 Java TLS（Android 2.1 老网络层）
 * - 信任所有证书并跳过主机名校验（兼容老设备自签名/过期证书场景）
 * - 支持流式 SSE 响应和一次性 JSON 响应
 * - 支持 reasoning_content（DeepSeek 思考链）
 *
 * 本类【不】直接引用 okhttp，okhttp 只由 OkHttpWorker 通过 Class.forName
 * 在运行时按需加载，避免在 Android 2.1 上因类校验失败而 VerifyError 崩溃。
 */
public class AIClient {

    /**
     * 对 AI 请求的统一连接封装（适配中性 IHttpConnection）。
     */
    public static class Connection implements IHttpConnection {
        private final IHttpConnection mConn;
        private final int mCode;

        Connection(IHttpConnection conn) throws IOException {
            mConn = conn;
            mCode = conn.getResponseCode();
        }

        public int getResponseCode() {
            return mCode;
        }

        public InputStream getInputStream() throws IOException {
            return mConn.getInputStream();
        }

        public InputStream getErrorStream() throws IOException {
            return mConn.getErrorStream();
        }

        public String getHeaderField(String name) {
            return mConn.getHeaderField(name);
        }

        public void disconnect() {
            try {
                mConn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 构造 OpenAI 兼容协议请求体 JSON
     */
    public static String buildRequestBody(ModelConfig model, java.util.List<ChatMessage> history,
                                          String userMessage, java.util.List<String> images, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":");
        sb.append(jsonStr(model.modelId));
        sb.append(",\"messages\":[");

        boolean first = true;
        if (model.systemPrompt != null && model.systemPrompt.length() > 0) {
            sb.append("{\"role\":").append(jsonStr("system"))
              .append(",\"content\":").append(jsonStr(model.systemPrompt)).append("}");
            first = false;
        }
        if (history != null) {
            for (ChatMessage m : history) {
                if (m == null) continue;
                if (m.isSystem()) continue;
                if (!first) sb.append(",");
                sb.append("{\"role\":").append(jsonStr(m.role))
                  .append(",\"content\":").append(jsonStr(m.content)).append("}");
                first = false;
            }
        }
        if (userMessage != null && userMessage.length() > 0) {
            if (!first) sb.append(",");
            sb.append("{\"role\":").append(jsonStr("user"));
            if (model.multimodal && images != null && images.size() > 0) {
                sb.append(",\"content\":[");
                sb.append("{\"type\":\"text\",\"text\":").append(jsonStr(userMessage)).append("}");
                for (int i = 0; i < images.size(); i++) {
                    sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                       .append(jsonStr(images.get(i))).append("}}");
                }
                sb.append("]");
            } else {
                sb.append(",\"content\":").append(jsonStr(userMessage));
            }
            sb.append("}");
        }
        sb.append("]");

        sb.append(",\"temperature\":").append(formatDouble(model.temperature));
        if (model.maxTokens > 0) {
            sb.append(",\"max_tokens\":").append(model.maxTokens);
        }
        if (stream) {
            sb.append(",\"stream\":true");
        }
        appendOptimizationParams(model, sb);
        if (model.enableToolCalls) {
            sb.append(",\"tools\":[")
              .append("{\"type\":\"function\",\"function\":")
              .append("{\"name\":\"web_search\",")
              .append("\"description\":\"Search the web. YOU MUST USE THIS for current events, versions, releases, news. WRAP KEY TERMS IN QUOTES like \\\"Genshin\\\". 10 results are returned; identify 1-6 high-value URLs. After search, USE fetch_page to open each high-value URL one by one. Open a URL, check if it has complete info; if not, open the next one. Keep opening until you have enough to answer fully. NEVER answer from snippets alone.\",")
              .append("\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":")
              .append("{\"type\":\"string\",\"description\":\"Search keywords in user's language\"}},")
              .append("\"required\":[\"query\"],\"additionalProperties\":false}}},{\"type\":\"function\",\"function\":")
              .append("{\"name\":\"fetch_page\",")
              .append("\"description\":\"Open a URL and read full page content. REQUIRED after web_search. Open high-value URLs from search results one at a time. If one page lacks complete info, open the next. Only answer after finding all needed facts.\",")
              .append("\"parameters\":{\"type\":\"object\",\"properties\":{\"url\":")
              .append("{\"type\":\"string\",\"description\":\"The full URL to open from search results\"}},")
              .append("\"required\":[\"url\"],\"additionalProperties\":false}}},{\"type\":\"function\",\"function\":")
              .append("{\"name\":\"get_current_date\",")
              .append("\"description\":\"Get current date and time from device.\",")
              .append("\"parameters\":{\"type\":\"object\",\"properties\":{}")
              .append(",\"additionalProperties\":false}}}]");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendOptimizationParams(ModelConfig model, StringBuilder sb) {
        if (model == null || sb == null) return;
        if (!model.shouldShowReasoning()) return;
        sb.append(",\"include_reasoning\":true");
        sb.append(",\"chat_template_kwargs\":{\"enable_thinking\":true}");
    }

    /**
     * 建立到 API 的连接（流式 / 非流式通用）。
     */
    public static Connection connect(ModelConfig model, String body) throws Exception {
        Context ctx = OACApplication.getContext();
        if (ctx == null) {
            throw new IllegalStateException("OACApplication context not initialized");
        }
        return connect(ctx, model, body);
    }

    public static Connection connect(Context ctx, ModelConfig model, String body) throws Exception {
        NetRequest req = new NetRequest();
        req.url = model.apiUrl;
        req.method = "POST";
        req.contentType = "application/json; charset=utf-8";
        req.body = body.getBytes("UTF-8");
        req.headers = new java.util.HashMap<String, String>();
        req.headers.put("Accept", "text/event-stream, application/json");
        req.headers.put("Connection", "keep-alive");
        if (model.apiKey != null && model.apiKey.length() > 0) {
            req.headers.put("Authorization", "Bearer " + model.apiKey);
        }
        IHttpConnection conn = NetWorkerFactory.getWorker().connect(req);
        return new Connection(conn);
    }

    public static String readFullResponse(Connection conn) throws Exception {
        StreamDelta d = readFullResponseDelta(conn);
        return d.content;
    }

    public static StreamDelta readFullResponseDelta(Connection conn) throws Exception {
        int code = conn.getResponseCode();
        // 错误码用 getErrorStream()：老网络层（HttpURLConnection）在 4xx/5xx 上调
        // getInputStream() 会抛 IOException；okhttp 实现中两者等价（均为 body 流）。
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (is == null) {
            throw new Exception("HTTP " + code);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        String body = sb.toString();

        if (code >= 200 && code < 300) {
            return parseFullResponseDelta(body);
        } else {
            String msg = parseErrorMessage(body);
            throw new Exception("HTTP " + code + (msg != null ? ": " + msg : ""));
        }
    }

    private static String parseFullResponseContent(String body) {
        return parseFullResponseDelta(body).content;
    }

    private static StreamDelta parseFullResponseDelta(String body) {
        StreamDelta d = new StreamDelta();
        if (body == null || body.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject message = first.optJSONObject("message");
                if (message != null) {
                    d.content = safeOptString(message, "content");
                    d.reasoning = safeOptString(message, "reasoning_content");
                    if (d.reasoning.length() == 0) {
                        d.reasoning = safeOptString(message, "reasoning");
                    }
                    if (d.content.length() == 0) {
                        d.content = safeOptString(message, "text");
                    }
                    org.json.JSONArray toolCalls = message.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        org.json.JSONObject tc = toolCalls.getJSONObject(0);
                        d.isToolCall = true;
                        String tcid = safeOptString(tc, "id");
                        if (tcid.length() > 0) d.toolCallId = tcid;
                        org.json.JSONObject func = tc.optJSONObject("function");
                        if (func != null) {
                            String fn = safeOptString(func, "name");
                            if (fn.length() > 0) d.toolCallName = fn;
                            d.toolCallArgs = safeOptString(func, "arguments");
                        }
                    }
                    return d;
                }
                d.content = safeOptString(first, "text");
            }
        } catch (Exception e) {
        }
        return d;
    }

    private static String parseErrorMessage(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String msg = safeOptString(err, "message");
                if (msg.length() > 0) return msg;
            }
            String msg = safeOptString(json, "message");
            if (msg.length() > 0) return msg;
        } catch (Exception e) {
            if (body.length() > 200) body = body.substring(0, 200);
            return body;
        }
        return body;
    }

    public static StreamDelta parseStreamLine(String line) {
        StreamDelta d = new StreamDelta();
        if (line == null) return d;
        line = line.trim();
        if (line.length() == 0) return d;
        if (!line.startsWith("data:")) return d;
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) return null;
        if (data.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject delta = first.optJSONObject("delta");
                if (delta == null) {
                    delta = first.optJSONObject("message");
                }
                if (delta != null) {
                    d.content = safeOptString(delta, "content");
                    d.reasoning = safeOptString(delta, "reasoning_content");
                    if (d.reasoning.length() == 0) {
                        d.reasoning = safeOptString(delta, "reasoning");
                    }
                    if (d.content.length() == 0) {
                        d.content = safeOptString(delta, "text");
                    }
                    org.json.JSONArray toolCalls = delta.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        org.json.JSONObject tc = toolCalls.getJSONObject(0);
                        d.isToolCall = true;
                        String tcId = safeOptString(tc, "id");
                        if (tcId.length() > 0) d.toolCallId = tcId;
                        org.json.JSONObject func = tc.optJSONObject("function");
                        if (func != null) {
                            String fnName = safeOptString(func, "name");
                            if (fnName.length() > 0) d.toolCallName = fnName;
                            d.toolCallArgs = safeOptString(func, "arguments");
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return d;
    }

    private static String safeOptString(org.json.JSONObject obj, String key) {
        if (obj == null) return "";
        if (obj.isNull(key)) return "";
        try {
            String s = obj.optString(key, "");
            if (s == null || "null".equals(s)) return "";
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        while (hex.length() < 4) hex = "0" + hex;
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) d = 0.7;
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        String s = String.valueOf(d);
        if (s.indexOf('.') > 0) {
            while (s.length() > 1 && s.charAt(s.length() - 1) == '0') {
                s = s.substring(0, s.length() - 1);
            }
            if (s.length() > 1 && s.charAt(s.length() - 1) == '.') {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    /** 对任意 HttpURLConnection 应用 Trust-All SSL（供 WebSearchUtil 等复用） */
    public static void applyTrustAllSSL(java.net.HttpURLConnection conn) {
        // 已改用 OkHttp，旧入口保留空实现避免编译错误
    }
}
