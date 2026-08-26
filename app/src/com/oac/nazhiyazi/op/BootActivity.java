package com.oac.nazhiyazi.op;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import com.oac.nazhiyazi.op.util.SslHelper;

/**
 * 启动引导 Activity（LAUNCHER）。
 *
 * 在“连语言选择都没加载”之前，先让用户选择网络层运行模式：
 *  - Android 2.3+：使用 OkHttp（性能/流式最佳，默认）
 *  - Android 2.1：使用老网络层（HttpsURLConnection + SpongyCastle 纯 Java TLS）
 *
 * ⚠️ 本 Activity 不引用任何 okhttp 类，确保在 Android 2.1 上绝不会因
 * okhttp 类校验而崩溃。选择会被持久化；之后再进入应用会直接跳主界面。
 */
public class BootActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 幂等安装 SpongyCastle，保证老网络层随时可用
        try {
            SslHelper.installSpongyCastle();
        } catch (Throwable t) {
            // ignore
        }

        setContentView(R.layout.activity_boot);

        if (isModeChosen()) {
            // 已经选过模式，直接进入主界面（语言/隐私流程交给 MainActivity）
            startMain();
            return;
        }

        showModeDialog();
    }

    private boolean isModeChosen() {
        try {
            return SettingsManager.get(this).isNetModeChosen();
        } catch (Throwable t) {
            return false;
        }
    }

    private void showModeDialog() {
        final String[] names = {
                getString(R.string.boot_mode_23),
                getString(R.string.boot_mode_21)
        };
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.boot_title)
                .setCancelable(false)
                .setSingleChoiceItems(names, 0, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            String mode = (which == 1)
                                    ? SettingsManager.NET_MODE_LEGACY
                                    : SettingsManager.NET_MODE_OKHTTP;
                            SettingsManager.get(BootActivity.this).setNetMode(mode);
                            SettingsManager.get(BootActivity.this).setNetModeChosen(true);
                            NetWorkerFactory.reset();
                        } catch (Throwable t) {
                            // 即便保存失败也继续，使用默认 okhttp
                        }
                        dialog.dismiss();
                        startMain();
                    }
                })
                .create();
        // 屏蔽返回键，必须二选一
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_BACK;
            }
        });
        dlg.show();
    }

    private void startMain() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
        } catch (Throwable t) {
            // ignore
        }
        finish();
    }
}
