package com.oac.nazhiyazi.op;

import com.oac.nazhiyazi.op.util.SslHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * 老网络层（Android 2.1+ 路径）。
 *
 * 使用平台 HttpsURLConnection + SpongyCastle 纯 Java TLS（无 .so，全架构通用）
 * 进行握手，信任所有证书并跳过主机名校验（兼容老设备自签名/过期证书场景）。
 *
 * 回退策略（按用户要求）：
 *  1) 先用 HttpsURLConnection + SpongyCastle 自己尝试握手；
 *  2) 若握手/连接失败，再尝试反射加载 OkHttpWorker 走 okhttp；
 *  3) 若 okhttp 也崩（VerifyError 等），则抛“晚安”致命异常 —— 彻底放弃。
 */
public class LegacyWorker implements NetWorker {

    @Override
    public IHttpConnection connect(NetRequest req) throws Exception {
        try {
            return doConnect(req);
        } catch (Throwable t) {
            // 自己尝试失败，最后试一次 okhttp（反射加载，不污染类加载）
            NetWorker fallback = tryLoadOkHttp();
            if (fallback != null) {
                try {
                    return fallback.connect(req);
                } catch (Throwable t2) {
                    throw new Exception("晚安：老网络层与 okhttp 均无法连接（"
                            + safe(t) + " / " + safe(t2) + "）");
                }
            }
            throw new Exception("晚安：老网络层连接失败，且无法加载 okhttp（" + safe(t) + "）");
        }
    }

    private IHttpConnection doConnect(NetRequest req) throws Exception {
        // 确保 SpongyCastle 双 Provider 已安装（纯 Java TLS 1.2，兼容 Android 2.1）
        SslHelper.installSpongyCastle();

        URL url = new URL(req.url);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod(req.method != null ? req.method : "GET");
        conn.setInstanceFollowRedirects(true);

        if (req.headers != null) {
            for (Map.Entry<String, String> e : req.headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        // 老安卓 HttpClient 实现下，gzip 会缓冲整个响应导致 SSE 不流式；
        // 显式要求 identity 以尽量保证流式读取。
        conn.setRequestProperty("Accept-Encoding", "identity");

        if (conn instanceof HttpsURLConnection) {
            try {
                javax.net.ssl.SSLSocketFactory sf = SslHelper.createTrustAllSocketFactory();
                if (sf != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
                }
                ((HttpsURLConnection) conn).setHostnameVerifier(
                        SslHelper.createTrustAllHostnameVerifier());
            } catch (Throwable t) {
                // 回退系统默认 TLS
            }
        }

        if ("POST".equalsIgnoreCase(req.method) && req.body != null) {
            conn.setDoOutput(true);
            if (req.contentType != null) {
                conn.setRequestProperty("Content-Type", req.contentType);
            }
            // 流式写入，避免旧 HttpClient 缓冲整个请求体
            conn.setChunkedStreamingMode(0);
            OutputStream os = conn.getOutputStream();
            os.write(req.body);
            os.flush();
            os.close();
        }

        // ⚠️ 关键：在此 try 块内提前触发握手（getResponseCode 会真正建立连接）。
        // 这样 TLS 握手失败（Android 2.1 上老设备常见）能被下方 catch 捕获，
        // 进而回退到 okhttp 或抛“晚安”，而不是把异常泄露到 AIClient 构造器之外。
        // （注意：4xx/5xx 是正常响应，getResponseCode 不抛异常，不会误触发回退。）
        conn.getResponseCode();

        return new LegacyConnection(conn);
    }

    /** 反射加载 OkHttpWorker；任何失败（含 VerifyError）都返回 null */
    private static NetWorker tryLoadOkHttp() {
        try {
            Class<?> cls = Class.forName("com.oac.nazhiyazi.op.OkHttpWorker");
            return (NetWorker) cls.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "?";
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /** 把 HttpURLConnection 适配成中性 IHttpConnection */
    private static class LegacyConnection implements IHttpConnection {
        private final HttpURLConnection mConn;

        LegacyConnection(HttpURLConnection conn) {
            mConn = conn;
        }

        @Override
        public int getResponseCode() throws IOException {
            return mConn.getResponseCode();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mConn.getInputStream();
        }

        @Override
        public InputStream getErrorStream() throws IOException {
            return mConn.getErrorStream();
        }

        @Override
        public String getHeaderField(String name) {
            return mConn.getHeaderField(name);
        }

        @Override
        public void disconnect() {
            try {
                mConn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }
}
