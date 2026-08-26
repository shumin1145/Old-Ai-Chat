package com.oac.nazhiyazi.op;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简单联网搜索工具。兼容 Android 2.1 (API 7+)。
 *
 * 使用 Bing 搜索（国内可访问），提取搜索结果的标题、URL 和摘要。
 * 通过 NetWorkerFactory 选择网络层（OkHttp / 老网络层 + SpongyCastle）。
 *
 * 本类不直接引用 okhttp，避免 Android 2.1 上类校验失败。
 */
public class WebSearchUtil {

    /** 单条搜索结果最大字符数（标题 + URL + 摘要） */
    private static final int MAX_RESULT_LEN = 1200;
    /** 最多返回几条搜索结果 */
    private static final int MAX_RESULTS = 8;
    /** 搜索结果总长度上限 */
    private static final int MAX_TOTAL_LEN = 6000;
    /** fetch_page 单页最大字符数 */
    private static final int MAX_PAGE_LEN = 6000;
    /** 自动 fetch 前几条的网页正文 */
    private static final int AUTO_FETCH_TOP = 6;
    /** 自动 fetch 单页最大字符数 */
    private static final int AUTO_FETCH_LEN = 500;

    public static String search(String query) {
        try {
            if (query == null || query.length() == 0) return "(empty query)";
            String encoded = URLEncoder.encode(query, "UTF-8");
            String url = "https://cn.bing.com/search?q=" + encoded + "&setmkt=zh-CN&count=10&ensearch=1";
            NetRequest req = new NetRequest();
            req.url = url;
            req.method = "GET";
            req.headers = new java.util.HashMap<String, String>();
            req.headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 4.4; OAC/1.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/30.0.1599.92 Mobile Safari/537.36");
            req.headers.put("Accept-Encoding", "identity");
            req.headers.put("Accept", "text/html");
            req.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            IHttpConnection conn = NetWorkerFactory.getWorker().connect(req);
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "(search error: HTTP " + code + ")";
            String html = readAll(is);
            conn.disconnect();
            if (code < 200 || code >= 300) {
                String errBody = html;
                return "(search error: HTTP " + code + (errBody.length() > 0 ? " " + errBody.substring(0, Math.min(errBody.length(), 100)) : "") + ")";
            }
            if (html.length() == 0) return "(no results)";
            ArrayList<String> urls = new ArrayList<String>();
            String result = extractBingResults(html, urls);
            if (result.length() == 0) return "(no results)";
            StringBuilder out = new StringBuilder(result);
            for (String pageUrl : urls) {
                String pageText = fetchPageText(pageUrl, AUTO_FETCH_LEN);
                if (pageText.length() > 40 && !pageText.startsWith("(")) {
                    out.append("\n[网页正文 ").append(pageUrl).append("]\n").append(pageText).append("\n");
                }
            }
            if (out.length() > MAX_TOTAL_LEN) out.setLength(MAX_TOTAL_LEN);
            return out.toString();
        } catch (Exception e) {
            String msg = e.getMessage();
            return "(search error: " + (msg != null ? msg : e.getClass().getSimpleName()) + ")";
        }
    }

    /**
     * 从 Bing HTML 中提取搜索结果。
     */
    private static String extractBingResults(String html, ArrayList<String> urls) {
        StringBuilder out = new StringBuilder();
        String[] blocks = html.split("class=\"b_algo\"");
        int count = 0;
        for (int i = 1; i < blocks.length && count < MAX_RESULTS; i++) {
            String block = blocks[i];
            String title = extractTagText(block, "h2");
            String url = extractFirstHref(block);
            String snippet = extractSnippet(block);

            if ((title.length() == 0 && snippet.length() == 0) || url.length() == 0) continue;
            if (title.length() == 0) title = "(no title)";

            count++;
            StringBuilder item = new StringBuilder();
            item.append(count).append(". ").append(cleanWhitespace(title)).append("\n");
            item.append("URL: ").append(url).append("\n");
            if (snippet.length() > 0) {
                item.append("摘要: ").append(cleanWhitespace(snippet)).append("\n");
            }

            String itemStr = item.toString();
            if (itemStr.length() > MAX_RESULT_LEN) {
                itemStr = itemStr.substring(0, MAX_RESULT_LEN) + "...";
            }
            out.append(itemStr).append("\n");

            if (urls != null && urls.size() < AUTO_FETCH_TOP) {
                urls.add(url);
            }
        }
        if (count == 0) {
            String text = stripHtml(html);
            if (text.length() > 2000) text = text.substring(0, 2000) + "...";
            return text;
        }
        return out.toString();
    }

    /** 提取第一个 <a href="..."> 中的真实 URL（处理 Bing 重定向包装） */
    private static String extractFirstHref(String block) {
        if (block == null) return "";
        Pattern p = Pattern.compile("<a[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(block);
        while (m.find()) {
            String href = m.group(1).trim();
            if (href.length() == 0) continue;
            if (href.startsWith("/")) continue;

            String lower = href.toLowerCase();
            if (lower.contains("bing.com/ck/a") || lower.contains("&u=")) {
                String real = extractUrlFromBingCk(href);
                if (real.length() > 0) return real;
            }
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return href;
            }
        }
        return "";
    }

    /** 从 Bing /ck/a 链接的 u 参数中还原真实 URL */
    private static String extractUrlFromBingCk(String href) {
        if (href == null) return "";
        int uIdx = href.indexOf("&u=");
        if (uIdx < 0) uIdx = href.indexOf("?u=");
        if (uIdx < 0) return "";
        String encoded = href.substring(uIdx + 3);
        int end = encoded.indexOf("&");
        if (end > 0) encoded = encoded.substring(0, end);
        try {
            String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
            String lower = decoded.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://")) return decoded;
            try {
                byte[] bytes = org.spongycastle.util.encoders.Base64.decode(decoded);
                String b64 = new String(bytes, "UTF-8");
                lower = b64.toLowerCase();
                if (lower.startsWith("http://") || lower.startsWith("https://")) return b64;
            } catch (Throwable t) {
            }
        } catch (Exception e) {
        }
        return "";
    }

    /** 提取指定标签内的纯文本 */
    private static String extractTagText(String block, String tag) {
        if (block == null || tag == null) return "";
        Pattern open = Pattern.compile("<" + tag + "[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher om = open.matcher(block);
        if (!om.find()) return "";
        int start = om.end();
        String closeTag = "</" + tag + ">";
        int end = block.indexOf(closeTag, start);
        if (end < 0) end = block.length();
        String inner = block.substring(start, end);
        return stripHtml(inner);
    }

    /** 提取摘要 */
    private static String extractSnippet(String block) {
        if (block == null) return "";
        String p = extractTagText(block, "p");
        if (p.length() > 10) return p;
        int capStart = block.indexOf("class=\"b_caption\"");
        if (capStart >= 0) {
            int divEnd = block.indexOf("</div>", capStart);
            if (divEnd > capStart) {
                String cap = block.substring(capStart, divEnd);
                String capText = stripHtml(cap);
                if (capText.length() > 10) return capText;
            }
        }
        String all = stripHtml(block);
        return all;
    }

    /** 打开指定 URL 并返回纯文本内容 */
    public static String fetchPage(String urlStr) {
        return fetchPageText(urlStr, MAX_PAGE_LEN);
    }

    /** 打开指定 URL 并返回限定长度的纯文本内容 */
    private static String fetchPageText(String urlStr, int maxLen) {
        try {
            if (urlStr == null || urlStr.length() == 0) return "(empty url)";
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) urlStr = "https://" + urlStr;
            NetRequest req = new NetRequest();
            req.url = urlStr;
            req.method = "GET";
            req.headers = new java.util.HashMap<String, String>();
            req.headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 4.4; OAC/1.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/30.0.1599.92 Mobile Safari/537.36");
            req.headers.put("Accept-Encoding", "identity");
            req.headers.put("Accept", "text/html");
            req.headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            IHttpConnection conn = NetWorkerFactory.getWorker().connect(req);
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "(page error: HTTP " + code + ")";
            String html = readAll(is);
            conn.disconnect();
            String text = extractPageText(html);
            if (text.length() > maxLen) text = text.substring(0, maxLen) + "...";
            if (text.length() == 0) return "(page empty)";
            return text;
        } catch (Exception e) {
            String msg = e.getMessage();
            return "(page error: " + (msg != null ? msg : e.getClass().getSimpleName()) + ")";
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }

    /** 从 HTML 中提取较干净的正文文本 */
    private static String extractPageText(String html) {
        if (html == null) return "";
        int bodyStart = html.indexOf("<body");
        int bodyEnd = html.indexOf("</body>");
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            html = html.substring(bodyStart, bodyEnd);
        }
        html = html.replaceAll("(?i)<script[^>]*>[\\s\\S]*?</script>", " ")
                   .replaceAll("(?i)<style[^>]*>[\\s\\S]*?</style>", " ")
                   .replaceAll("(?i)<nav[^>]*>[\\s\\S]*?</nav>", " ")
                   .replaceAll("(?i)<header[^>]*>[\\s\\S]*?</header>", " ")
                   .replaceAll("(?i)<footer[^>]*>[\\s\\S]*?</footer>", " ")
                   .replaceAll("(?i)<aside[^>]*>[\\s\\S]*?</aside>", " ")
                   .replaceAll("(?i)<noscript[^>]*>[\\s\\S]*?</noscript>", " ")
                   .replaceAll("(?i)<!--[\\s\\S]*?-->", " ");
        String article = extractTagContent(html, "article");
        if (article.length() > 200) return cleanWhitespace(article);
        String main = extractTagContent(html, "main");
        if (main.length() > 200) return cleanWhitespace(main);
        return cleanWhitespace(stripHtml(html));
    }

    /** 提取指定双标签内的文本 */
    private static String extractTagContent(String html, String tag) {
        if (html == null || tag == null) return "";
        Pattern open = Pattern.compile("<" + tag + "[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher om = open.matcher(html);
        if (!om.find()) return "";
        int start = om.end();
        String closeTag = "</" + tag + ">";
        int end = html.indexOf(closeTag, start);
        if (end < 0) return "";
        return stripHtml(html.substring(start, end));
    }

    /** 剥离 HTML 标签和实体 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", " ")
                .replaceAll("&[a-zA-Z0-9#]+;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** 清理多余空白 */
    private static String cleanWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }
}
