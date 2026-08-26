package com.oac.nazhiyazi.op;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * 应用入口。
 *
 * 全局安装 SpongyCastle 纯 Java TLS 1.2 能力（无 .so，全架构通用），使 Android 2.3 也能协商 TLS 1.2。
 *
 * 关键修正：必须【同时】注册 SpongyCastle 的加密 Provider（BouncyCastleProvider，名 "SC"）
 * 与 JSSE Provider（BouncyCastleJsseProvider，名 "SCJSSE"）。
 * 旧版只注册了 JSSE，没有加密后端，握手会抛 TlsFatalAlert internal_error(80)。
 */
public class OACApplication extends Application {

    private static final String TAG = "OACApplication";
    private static Context sContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = getApplicationContext();
        // 同时安装 SC 加密 Provider + JSSE Provider（幂等）
        com.oac.nazhiyazi.op.util.SslHelper.installSpongyCastle();
    }

    public static Context getContext() {
        return sContext;
    }
}
