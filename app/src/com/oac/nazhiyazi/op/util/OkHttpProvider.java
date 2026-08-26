package com.oac.nazhiyazi.op.util;

import android.content.Context;
import android.util.Log;

import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.Dispatcher;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 全局 OkHttpClient 提供者。
 *
 * 统一管理所有网络请求的 OkHttpClient，使代理、SSL 等配置修改后能立即生效。
 * 老安卓 TLS 兼容性：SpongyCastle 纯 Java TLS 1.2 + 信任所有证书/主机名。
 */
public final class OkHttpProvider {

    private static final String TAG = "OkHttpProvider";
    private static final String USER_AGENT = "OAC/1.4 (Android 2.3+; OldAIChatOpen)";

    private static OkHttpClient sClient;

    private OkHttpProvider() {}

    public static synchronized OkHttpClient get(Context ctx) {
        if (sClient == null) {
            sClient = build(ctx);
        }
        return sClient;
    }

    public static synchronized void rebuild(Context ctx) {
        sClient = build(ctx);
    }

    private static OkHttpClient build(Context ctx) {
        OkHttpClient client = new OkHttpClient();
        client.setConnectTimeout(20, TimeUnit.SECONDS);
        client.setReadTimeout(60, TimeUnit.SECONDS);
        client.setWriteTimeout(30, TimeUnit.SECONDS);

        // 老安卓 TLS 兼容性：SpongyCastle 纯 Java TLS 1.2
        try {
            javax.net.ssl.SSLSocketFactory sf = SslHelper.createTrustAllSocketFactory();
            if (sf != null) {
                client.setSslSocketFactory(sf);
                client.setHostnameVerifier(SslHelper.createTrustAllHostnameVerifier());
            }
        } catch (Exception e) {
            Log.w(TAG, "SSL init failed: " + e.getMessage());
        }

        client.interceptors().add(new UserAgentInterceptor());
        client.interceptors().add(new RetryInterceptor(1));
        client.setDispatcher(new Dispatcher(Executors.newFixedThreadPool(4)));

        applyCache(client, ctx);
        applyProxy(client);
        return client;
    }

    private static void applyCache(OkHttpClient client, Context ctx) {
        try {
            File cacheDir = new File(ctx.getCacheDir(), "okhttp-cache");
            client.setCache(new com.squareup.okhttp.Cache(cacheDir, 50 * 1024 * 1024L));
        } catch (Exception e) {
            Log.w(TAG, "cache init failed: " + e.getMessage());
        }
    }

    private static void applyProxy(OkHttpClient client) {
        // OAC 暂无设置页代理开关，保留占位，后续可接入 SettingsManager
        if (Boolean.FALSE.booleanValue()) {
            try {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8080));
                client.setProxy(proxy);
            } catch (Exception e) {
                Log.w(TAG, "proxy init failed: " + e.getMessage());
            }
        }
    }

    private static class UserAgentInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            if (original.header("User-Agent") != null) {
                return chain.proceed(original);
            }
            Request request = original.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build();
            return chain.proceed(request);
        }
    }

    /**
     * 失败重试拦截器：对 GET/HEAD 请求，遇到 IOException 或 5xx/408/429 时重试一次。
     * ARMv5/6 老设备上 SpongyCastle TLS 握手偶发 40/46，重试后通常能成功。
     */
    private static class RetryInterceptor implements Interceptor {
        private final int mMaxRetries;

        RetryInterceptor(int maxRetries) {
            mMaxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException exception = null;
            for (int i = 0; i <= mMaxRetries; i++) {
                try {
                    response = chain.proceed(request);
                    if (response.isSuccessful() || !shouldRetry(request, response)) {
                        return response;
                    }
                    try {
                        response.body().close();
                    } catch (Exception ignored) {}
                } catch (IOException e) {
                    exception = e;
                    if (i == mMaxRetries) {
                        throw e;
                    }
                }
            }
            if (response != null) {
                return response;
            }
            throw exception;
        }

        private boolean shouldRetry(Request request, Response response) {
            if (!"GET".equals(request.method()) && !"HEAD".equals(request.method())) {
                return false;
            }
            int code = response.code();
            return code >= 500 || code == 408 || code == 429;
        }
    }
}
