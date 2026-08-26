package com.oac.nazhiyazi.op;

import android.util.Log;

import com.oac.nazhiyazi.op.util.SslHelper;

/**
 * 网络层工厂：根据持久化的运行模式选择具体实现。
 *
 *  - 模式 = okhttp（Android 2.3+ 默认）：尝试反射加载 OkHttpWorker；
 *    若加载失败（例如 Android 2.1 上 okhttp 类校验不过抛 VerifyError），
 *    自动回退到 LegacyWorker。
 *  - 模式 = legacy（Android 2.1）：直接用 LegacyWorker，由它在握手失败时
 *    再尝试反射加载 okhttp，再失败则“晚安”。
 *
 * 关键点：本类【绝不】静态 import okhttp，OkHttpWorker 只通过
 * Class.forName 加载，因此本类在任意安卓版本上都能安全加载。
 */
public final class NetWorkerFactory {

    private static final String TAG = "NetWorkerFactory";

    private static NetWorker sCached;

    private NetWorkerFactory() {
    }

    public static synchronized NetWorker getWorker() {
        if (sCached != null) {
            return sCached;
        }
        String mode = getMode();
        NetWorker worker;
        if (SettingsManager.NET_MODE_OKHTTP.equals(mode)) {
            worker = tryCreateOkHttp();
            if (worker == null) {
                Log.w(TAG, "okhttp 模式但加载失败（可能运行在 Android 2.1），回退 LegacyWorker");
                worker = new LegacyWorker();
            }
        } else {
            worker = new LegacyWorker();
        }
        sCached = worker;
        return worker;
    }

    /** 模式切换后清除缓存，使下次请求使用新模式 */
    public static synchronized void reset() {
        sCached = null;
    }

    /** 反射创建 OkHttpWorker；任何失败（含 VerifyError）返回 null */
    private static NetWorker tryCreateOkHttp() {
        try {
            Class<?> cls = Class.forName("com.oac.nazhiyazi.op.OkHttpWorker");
            return (NetWorker) cls.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "OkHttpWorker 加载失败: " + t);
            return null;
        }
    }

    private static String getMode() {
        try {
            android.content.Context ctx = OACApplication.getContext();
            if (ctx != null) {
                return SettingsManager.get(ctx).getNetMode();
            }
        } catch (Throwable t) {
            // ignore
        }
        return SettingsManager.NET_MODE_OKHTTP;
    }
}
