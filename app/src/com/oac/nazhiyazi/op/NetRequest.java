package com.oac.nazhiyazi.op;

import java.util.Map;

/**
 * 网络请求的中性描述（不依赖任何具体 HTTP 实现，如 okhttp）。
 *
 * 这样 AIClient / WebSearchUtil 等上层调用方完全不引用 okhttp 类，
 * 在 Android 2.1 等低版本上即使 okhttp 无法被虚拟机校验，也不会在
 * 类加载期崩溃（VerifyError）。okhttp 只由 OkHttpWorker 通过
 * Class.forName 在运行时按需加载。
 */
public class NetRequest {
    /** 完整请求 URL */
    public String url;
    /** 请求方法，默认 GET。支持 GET / POST 等。 */
    public String method = "GET";
    /** 请求头 */
    public Map<String, String> headers;
    /** POST 请求体（原始字节，避免字符串编码歧义） */
    public byte[] body;
    /** POST 时的 Content-Type，例如 application/json */
    public String contentType;

    public NetRequest() {}
}
