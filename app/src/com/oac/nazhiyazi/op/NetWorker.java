package com.oac.nazhiyazi.op;

/**
 * 网络层抽象。两种实现：
 *  - OkHttpWorker：基于 okhttp 2.x（Android 2.3+，性能/流式最佳），运行时反射加载。
 *  - LegacyWorker：基于 HttpsURLConnection + SpongyCastle 纯 Java TLS（Android 2.1+）。
 *
 * 调用方（AIClient / WebSearchUtil）只依赖此接口，不依赖任何具体实现。
 */
public interface NetWorker {
    /**
     * 发起一次请求并返回连接封装。
     *
     * @param req 中性请求描述
     * @return 连接封装（实现 IHttpConnection）
     * @throws Exception 连接失败、握手失败等任意异常（含“晚安”致命错误）
     */
    IHttpConnection connect(NetRequest req) throws Exception;
}
