package com.oac.nazhiyazi.op.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/HTTPS 工具。使用 SslHelper 实现 TLS 1.2 兼容，兼容 Android 2.3+。
 */
public class NetWorkUtil {

    public static final String USER_AGENT_WEB = "Mozilla/5.0 (Linux; Android 4.4; OAC/1.4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/30.0.1599.92 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final int MAX_REDIRECT_COUNT = 5;

    private static SSLSocketFactory sTrustAllSslFactory;

    public static synchronized SSLSocketFactory getTrustAllSSLSocketFactory() {
        if (sTrustAllSslFactory == null) {
            sTrustAllSslFactory = SslHelper.createTrustAllSocketFactory();
        }
        return sTrustAllSslFactory;
    }

    public static String get(String url) throws IOException {
        return get(url, null);
    }

    public static String get(String url, List headers) throws IOException {
        return getInternal(url, headers, 0);
    }

    private static String getInternal(String url, List headers, int retryCount) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = createConnection(url, "GET", headers);
            conn.connect();
            int responseCode = conn.getResponseCode();
            if (responseCode == 301 || responseCode == 302 || responseCode == 307) {
                return handleRedirect(conn, url, headers, "GET", null, retryCount + 1);
            }
            return readResponse(conn, responseCode);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String post(String url, String data, List headers) throws IOException {
        return post(url, data, headers, "application/json");
    }

    public static String post(String url, String data, List headers, String contentType) throws IOException {
        return postInternal(url, data, headers, contentType, 0);
    }

    private static String postInternal(String url, String data, List headers, String contentType, int retryCount) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = createConnection(url, "POST", headers);
            conn.setRequestProperty("Content-Type", contentType + "; charset=utf-8");
            if (data != null && data.length() > 0) {
                OutputStream os = conn.getOutputStream();
                os.write(data.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int responseCode = conn.getResponseCode();
            if (responseCode == 301 || responseCode == 302 || responseCode == 307) {
                return handleRedirect(conn, url, headers, "POST", data, retryCount + 1);
            }
            return readResponse(conn, responseCode);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection createConnection(String url, String method, List headers) throws IOException {
        URL requestUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
        if (url.startsWith("https") && conn instanceof HttpsURLConnection) {
            SSLSocketFactory sslFactory = getTrustAllSSLSocketFactory();
            if (sslFactory != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(sslFactory);
                ((HttpsURLConnection) conn).setHostnameVerifier(SslHelper.createTrustAllHostnameVerifier());
            }
        }
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput("POST".equals(method) || "PUT".equals(method));
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("User-Agent", USER_AGENT_WEB);
        conn.setRequestProperty("Accept", "text/event-stream, application/json, */*");
        applyHeaders(conn, headers);
        return conn;
    }

    private static void applyHeaders(HttpURLConnection conn, List headers) {
        if (headers == null) return;
        for (int i = 0; i < headers.size() - 1; i += 2) {
            Object key = headers.get(i);
            Object value = headers.get(i + 1);
            if (key != null && value != null) {
                conn.setRequestProperty(String.valueOf(key), String.valueOf(value));
            }
        }
    }

    private static String handleRedirect(HttpURLConnection conn, String originalUrl, List headers, String method, String postData, int retryCount) throws IOException {
        if (retryCount > MAX_REDIRECT_COUNT) {
            throw new IOException("Too many redirects");
        }
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        if (location == null || location.length() == 0) {
            throw new IOException("Redirect missing Location");
        }
        if (!location.startsWith("http")) {
            int schemeEnd = originalUrl.indexOf("://");
            int slashIndex = (schemeEnd > 0) ? originalUrl.indexOf("/", schemeEnd + 3) : originalUrl.indexOf("/", 8);
            if (slashIndex > 0) {
                location = originalUrl.substring(0, slashIndex) + "/" + location;
            } else {
                location = originalUrl + "/" + location;
            }
        }
        if ("POST".equals(method) && postData != null) {
            return postInternal(location, postData, headers, "application/json", retryCount + 1);
        } else {
            return getInternal(location, headers, retryCount + 1);
        }
    }

    private static String readResponse(HttpURLConnection conn, int responseCode) throws IOException {
        InputStream is = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } finally {
            is.close();
        }
    }

    public static byte[] readStream(InputStream inStream) throws IOException {
        java.io.ByteArrayOutputStream outStream = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inStream.read(buffer)) != -1) {
            outStream.write(buffer, 0, len);
        }
        outStream.close();
        inStream.close();
        return outStream.toByteArray();
    }
}
