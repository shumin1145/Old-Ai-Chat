package com.oac.nazhiyazi.op;

import java.io.IOException;
import java.io.InputStream;

/**
 * 网络连接的中性封装接口（不依赖 okhttp / HttpURLConnection 任一具体实现）。
 *
 * 上层（AIClient / WebSearchUtil）只通过此接口读取响应，从而与具体实现解耦，
 * 也避免把 okhttp 的 import 带进会被低版本安卓加载的类里。
 */
public interface IHttpConnection {
    /** HTTP 响应状态码 */
    int getResponseCode() throws IOException;

    /** 响应体输入流（成功响应） */
    InputStream getInputStream() throws IOException;

    /** 错误响应体输入流（4xx/5xx）。okhttp 实现中与 getInputStream 等价。 */
    InputStream getErrorStream() throws IOException;

    /** 读取响应头字段 */
    String getHeaderField(String name);

    /** 断开连接、释放资源 */
    void disconnect();
}
