package com.oac.nazhiyazi.op.util;

import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * OAC beta：老安卓 TLS 兼容帮助类（纯 Java，无 .so，全架构通用）。
 *
 * 与旧版的核心区别（旧版在老安卓上抛 TlsFatalAlert internal_error(80) 的根因）：
 *   旧版只把 SpongyCastle 的【JSSE】Provider（SCJSSE）注册进 Security，
 *   却【没有】注册 SpongyCastle 的【加密】Provider（BouncyCastleProvider，名为 "SC"）。
 *   JSSE 层没有加密后端可用（RSA / ECDHE / AES / SHA 都找不到实现），
 *   握手直接 internal_error(80)。
 *   同时旧版没有给 SpongyCastle 的 SSLSocket 设置 SNI，Cloudflare 等前置 SNI 的
 *   API（DeepSeek / OpenRouter / Kimi）会直接中断握手。
 *
 * 本版修正：
 *   1) 同时注册 SC 加密 Provider("SC") 与 SC JSSE Provider("SCJSSE")。
 *   2) 在每次建连时给 SSLSocket 设置 SNI（SpongyCastle 走 BCSSLParameters.setServerNames，
 *      平台 socket 走 setHostname 反射），避免 SNI 缺失导致的握手失败。
 *   3) 显式启用 TLSv1.2/1.1/1.0 协议与现代加密套件。
 *   4) 协议顺序：SpongyCastle TLS1.2（纯 Java，覆盖 Android 2.3+）→ 平台 TLS1.2（API16+）
 *      → 平台 TLS（兜底）。全程不依赖任何 native .so，故在所有 CPU 架构上都可用。
 */
public final class SslHelper {

    private static final String TAG = "SslHelper";

    private static final String SC_CRYPTO = "SC";     // SpongyCastle 加密 Provider 名
    private static final String SC_JSSE = "SCJSSE";   // SpongyCastle JSSE Provider 名

    /** 期望启用的协议顺序（TLS 1.2 优先）。 */
    private static final String[] PREFERRED_PROTOCOLS = {
            "TLSv1.2", "TLSv1.1", "TLSv1"
    };

    /** 现代加密套件（GCM/ECDHE 优先），按 socket 实际支持情况过滤后再启用。 */
    private static final String[] PREFERRED_CIPHERS = {
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_RSA_WITH_3DES_EDE_CBC_SHA"
    };

    private SslHelper() {}

    /**
     * 安装 SpongyCastle 双 Provider（加密 + JSSE）。幂等，重复调用安全。
     * 用反射按类名加载，避免 SC 未打包进 APK 时直接 NoClassDefFoundError 崩溃。
     */
    public static synchronized void installSpongyCastle() {
        try {
            if (Security.getProvider(SC_CRYPTO) == null) {
                Provider crypto = (Provider) Class
                        .forName("org.spongycastle.jce.provider.BouncyCastleProvider")
                        .getConstructor().newInstance();
                Security.insertProviderAt(crypto, 1);
            }
            if (Security.getProvider(SC_JSSE) == null) {
                Provider jsse = (Provider) Class
                        .forName("org.spongycastle.jsse.provider.BouncyCastleJsseProvider")
                        .getConstructor().newInstance();
                Security.insertProviderAt(jsse, 1);
            }
            Log.i(TAG, "SpongyCastle installed: crypto=" + (Security.getProvider(SC_CRYPTO) != null)
                    + " jsse=" + (Security.getProvider(SC_JSSE) != null));
        } catch (Throwable t) {
            Log.e(TAG, "installSpongyCastle failed: " + t);
        }
    }

    /** 构造信任所有证书的 SSLSocketFactory；优先 TLS 1.2，并在建连时强制协议/SNI。 */
    public static SSLSocketFactory createTrustAllSocketFactory() {
        SSLContext ctx = buildTlsContext();
        if (ctx == null) {
            Log.w(TAG, "buildTlsContext 返回 null，将回退系统默认 TLS");
            return null;
        }
        return new TlsSocketFactory(ctx.getSocketFactory());
    }

    /** 构造信任所有主机名的 HostnameVerifier。 */
    public static HostnameVerifier createTrustAllHostnameVerifier() {
        return new TrustAllHostVerifier();
    }

    /**
     * 构造一个 TLS 1.2 的 SSLContext，按优先级尝试：
     *   1) SpongyCastle TLSv1.2（纯 Java，覆盖 Android 2.3+，全架构）
     *   2) 平台 TLSv1.2（API 16+）
     *   3) 平台 TLS（兜底，由 TlsSocketFactory 尽量启用 1.2）
     */
    private static SSLContext buildTlsContext() {
        // 1) SpongyCastle（纯 Java，无 .so）—— 老安卓唯一能出 TLS 1.2 的路径
        try {
            installSpongyCastle();
            SSLContext ctx = SSLContext.getInstance("TLSv1.2", SC_JSSE);
            ctx.init(EMPTY_KEY_MANAGERS, TRUST_ALL, new SecureRandom());
            Log.d(TAG, "TLS context = SpongyCastle(pure-Java, TLSv1.2)");
            return ctx;
        } catch (Throwable t) {
            Log.d(TAG, "SpongyCastle TLSv1.2 unavailable: " + t);
        }

        // 2) 平台默认 Provider 暴露的 TLSv1.2（API 16+）
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(EMPTY_KEY_MANAGERS, TRUST_ALL, new SecureRandom());
            Log.d(TAG, "TLS context = Platform(TLSv1.2)");
            return ctx;
        } catch (Throwable t) {
            Log.d(TAG, "Platform TLSv1.2 unavailable: " + t);
        }

        // 3) 平台默认 TLS（兜底）
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(EMPTY_KEY_MANAGERS, TRUST_ALL, new SecureRandom());
            Log.d(TAG, "TLS context = Platform(TLS)");
            return ctx;
        } catch (Throwable t) {
            Log.d(TAG, "Platform TLS unavailable: " + t);
        }

        return null;
    }

    private static final KeyManager[] EMPTY_KEY_MANAGERS = { new EmptyX509KeyManager() };
    private static final TrustManager[] TRUST_ALL = { new TrustAllManager() };

    /** 空 X509KeyManager：任何情况下都不发客户端证书，消除 certificate_unknown(46)。 */
    private static class EmptyX509KeyManager implements X509KeyManager {
        public String[] getClientAliases(String keyType, java.security.Principal[] issuers) { return null; }
        public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) { return null; }
        public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return null; }
        public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) { return null; }
        public java.security.cert.X509Certificate[] getCertificateChain(String alias) { return null; }
        public java.security.PrivateKey getPrivateKey(String alias) { return null; }
    }

    /** 包装底层 SSLSocketFactory：每次建连强制启用 TLS 协议/SNI/现代套件。 */
    private static class TlsSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory mDelegate;

        TlsSocketFactory(SSLSocketFactory delegate) {
            mDelegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return mDelegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return mDelegate.getSupportedCipherSuites();
        }

        private Socket patch(Socket socket, String host) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                enableProtocols(ssl);
                enableCiphers(ssl);
                setSni(ssl, host);
            }
            return socket;
        }

        private void enableProtocols(SSLSocket ssl) {
            try {
                String[] supported = ssl.getSupportedProtocols();
                List<String> enabled = new ArrayList<String>();
                for (String want : PREFERRED_PROTOCOLS) {
                    if (Arrays.asList(supported).contains(want)) {
                        enabled.add(want);
                    }
                }
                if (!enabled.isEmpty()) {
                    ssl.setEnabledProtocols(enabled.toArray(new String[0]));
                }
            } catch (Throwable t) {
                Log.w(TAG, "enableProtocols failed: " + t);
            }
        }

        private void enableCiphers(SSLSocket ssl) {
            try {
                String[] supported = ssl.getSupportedCipherSuites();
                List<String> supportedList = Arrays.asList(supported);
                List<String> enabled = new ArrayList<String>();
                for (String want : PREFERRED_CIPHERS) {
                    if (supportedList.contains(want)) {
                        enabled.add(want);
                    }
                }
                if (!enabled.isEmpty()) {
                    ssl.setEnabledCipherSuites(enabled.toArray(new String[0]));
                }
            } catch (Throwable t) {
                Log.w(TAG, "enableCiphers failed: " + t);
            }
        }

        /**
         * 设置 SNI：
         *  - SpongyCastle 的 SSLSocket：通过 ProvSSLParameters.setServerNames(List<BCSNIServerName>) 设置；
         *  - 平台 SSLSocket：通过反射 setHostname / setUseSessionTickets 设置。
         * OkHttp 2.x 只会给【平台】socket 反射设 SNI，不会给 SpongyCastle socket 设，
         * 所以这里必须自己给 SpongyCastle socket 补上 SNI，否则 Cloudflare 前置的 API 会中断握手。
         */
        private void setSni(SSLSocket ssl, String host) {
            if (host == null) return;
            try {
                String cn = ssl.getClass().getName();
                if (cn.contains("spongycastle") || cn.contains("bouncycastle")) {
                    SSLParameters params = ssl.getSSLParameters();
                    Method setServerNames = params.getClass().getMethod("setServerNames", List.class);
                    Class<?> bcSniHost = Class.forName("org.spongycastle.jsse.BCSNIHostName");
                    Object sni = bcSniHost.getConstructor(String.class).newInstance(host);
                    List<Object> names = new ArrayList<Object>();
                    names.add(sni);
                    setServerNames.invoke(params, names);
                    ssl.setSSLParameters(params);
                    Log.d(TAG, "SNI set (SpongyCastle) for " + host);
                    return;
                }
                // 平台 socket：尽力设置 hostname / session tickets
                try {
                    ssl.getClass().getMethod("setHostname", String.class).invoke(ssl, host);
                } catch (Throwable ignored) {}
                try {
                    ssl.getClass().getMethod("setUseSessionTickets", boolean.class).invoke(ssl, true);
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                Log.d(TAG, "setSni skipped: " + t);
            }
        }

        @Override
        public Socket createSocket() throws IOException {
            return patch(mDelegate.createSocket(), null);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(mDelegate.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(mDelegate.createSocket(host, port), host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(mDelegate.createSocket(host, port, localHost, localPort), host);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(mDelegate.createSocket(host, port), null);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(mDelegate.createSocket(address, port, localAddress, localPort), null);
        }
    }

    /** 信任所有证书的 TrustManager（普通 X509TrustManager，API 1+ 即可，兼容 Android 2.3）。 */
    private static class TrustAllManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static class TrustAllHostVerifier implements HostnameVerifier {
        public boolean verify(String hostname, SSLSession session) { return true; }
    }
}
