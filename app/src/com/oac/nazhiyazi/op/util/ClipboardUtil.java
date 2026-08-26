package com.oac.nazhiyazi.op.util;

import android.content.Context;

/**
 * 兼容 Android 2.3 的剪贴板写入工具。
 * API 11+ 使用 content.ClipboardManager + ClipData；低版本使用 text.ClipboardManager。
 * 高版本类通过反射/字符串隔离，避免在 Android 2.3 上触发 VerifyError。
 */
public class ClipboardUtil {

    private ClipboardUtil() {}

    public static void copyText(Context context, String text) {
        if (SdkHelper.getSdkInt() >= 11) {
            if (copyApi11(context, text)) return;
        }
        copyApi1(context, text);
    }

    private static boolean copyApi11(Context context, String text) {
        try {
            Object cm = context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            // ClipData.newPlainText("text", text)
            Class<?> clipDataClass = Class.forName("android.content.ClipData");
            Object clip = clipDataClass.getMethod("newPlainText", CharSequence.class, CharSequence.class)
                    .invoke(null, "text", text);
            cm.getClass().getMethod("setPrimaryClip", clipDataClass).invoke(cm, clip);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static void copyApi1(Context context, String text) {
        try {
            android.text.ClipboardManager cm =
                    (android.text.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setText(text);
            }
        } catch (Throwable ignored) {
        }
    }
}
