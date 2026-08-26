package com.oac.nazhiyazi.op;

import com.oac.nazhiyazi.op.util.OkHttpProvider;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * okhttp 2.x 网络实现（Android 2.3+ 推荐路径）。
 *
 * ⚠️ 本类是唯一直接 import com.squareup.okhttp.* 的类。
 * 它【绝不】被任何会被低版本安卓加载的类静态引用，只通过
 * NetWorkerFactory / LegacyWorker 里的 Class.forName("...OkHttpWorker")
 * 在运行时按需加载。这样在 Android 2.1 上即使 okhttp 无法被虚拟机校验，
 * 崩溃也被限制在 Class.forName 的 try/catch 内，不会蔓延到整个 App。
 */
public class OkHttpWorker implements NetWorker {

    @Override
    public IHttpConnection connect(NetRequest req) throws Exception {
        Request.Builder builder = new Request.Builder().url(req.url);
        if (req.headers != null) {
            for (Map.Entry<String, String> e : req.headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }
        if ("POST".equalsIgnoreCase(req.method) && req.body != null) {
            MediaType mt = req.contentType != null
                    ? MediaType.parse(req.contentType)
                    : MediaType.parse("application/octet-stream");
            builder.post(RequestBody.create(mt, req.body));
        } else if ("POST".equalsIgnoreCase(req.method)) {
            builder.post(RequestBody.create(MediaType.parse("text/plain"), new byte[0]));
        } else {
            builder.get();
        }
        Request request = builder.build();
        Response response = OkHttpProvider.get(OACApplication.getContext()).newCall(request).execute();
        return new OkHttpConnection(response);
    }

    /** 把 okhttp Response 适配成中性 IHttpConnection */
    private static class OkHttpConnection implements IHttpConnection {
        private final Response mResponse;
        private final ResponseBody mBody;
        private final int mCode;

        OkHttpConnection(Response response) {
            mResponse = response;
            mBody = response.body();
            mCode = response.code();
        }

        @Override
        public int getResponseCode() {
            return mCode;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return mBody != null ? mBody.byteStream() : null;
        }

        @Override
        public InputStream getErrorStream() throws IOException {
            return mBody != null ? mBody.byteStream() : null;
        }

        @Override
        public String getHeaderField(String name) {
            return mResponse.header(name);
        }

        @Override
        public void disconnect() {
            try {
                if (mBody != null) mBody.close();
            } catch (Exception ignored) {
            }
        }
    }
}
