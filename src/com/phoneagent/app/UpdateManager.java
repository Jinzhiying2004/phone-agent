package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新（热更新的应用内安装形态，非热补丁）。
 * 更新源为一个可配置的 version.json 地址，格式：
 * {"version":"2.43","versionCode":68,"apkUrl":"https://.../phone-agent-2.43.apk","notes":"更新说明"}
 * 所有回调都在后台线程触发，UI 侧自行 runOnUiThread。
 */
public class UpdateManager {

    public interface CheckListener {
        void onResult(boolean hasUpdate, String version, int versionCode,
                      String apkUrl, String notes, String err);
    }

    public interface DownloadListener {
        void onProgress(int percent);

        void onDone(File apk, String err);
    }

    public static String getSource(Context ctx) {
        return ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
                .getString("source", "");
    }

    public static void setSource(Context ctx, String url) {
        ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
                .edit().putString("source", url == null ? "" : url.trim()).apply();
    }

    public static int currentCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 检查更新源的 version.json，与本地 versionCode 比较 */
    public static void check(Context ctx, CheckListener cb) {
        final Context app = ctx.getApplicationContext();
        final String src = getSource(app);
        if (src == null || src.trim().isEmpty()) {
            cb.onResult(false, null, 0, null, null, "未设置更新源，请先设置更新源地址");
            return;
        }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(src.trim()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "phone-agent");
                c.connect();
                if (c.getResponseCode() != 200) {
                    cb.onResult(false, null, 0, null, null,
                            "更新源返回 " + c.getResponseCode());
                    return;
                }
                String body = readAll(c.getInputStream());
                JSONObject j = new JSONObject(body);
                int rc = j.optInt("versionCode", 0);
                String rv = j.optString("version", "");
                if (rc > currentCode(app)) {
                    cb.onResult(true, rv, rc, j.optString("apkUrl", ""),
                            j.optString("notes", ""), null);
                } else {
                    cb.onResult(false, rv, rc, null, null, null);
                }
            } catch (Exception e) {
                cb.onResult(false, null, 0, null, null,
                        "检查失败：" + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /** 下载 APK 到私有目录 files/updates/update.apk，带进度 */
    public static void download(Context ctx, String apkUrl, DownloadListener dl) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "phone-agent");
                c.connect();
                if (c.getResponseCode() != 200) {
                    dl.onDone(null, "下载失败，更新源返回 " + c.getResponseCode());
                    return;
                }
                int total = c.getContentLength();
                File dir = new File(app.getFilesDir(), "updates");
                dir.mkdirs();
                File out = new File(dir, "update.apk");
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long done = 0;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int p = (int) (done * 100 / total);
                            if (p != last) {
                                last = p;
                                dl.onProgress(p);
                            }
                        }
                    }
                }
                dl.onDone(out, null);
            } catch (Exception e) {
                dl.onDone(null, "下载失败：" + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /**
     * 调起系统安装。返回 null 表示已调起；
     * 返回 "need_permission" 表示需用户去设置里允许“安装未知应用”；
     * 其他字符串为错误原因。
     */
    public static String installApk(Context ctx, File apk) {
        if (Build.VERSION.SDK_INT >= 26
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            return "need_permission";
        }
        try {
            Uri uri = UpdateProvider.uriFor(apk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** 跳转到“允许安装未知应用”设置页（本应用） */
    public static void openUnknownSourcesSetting(Context ctx) {
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Exception e) {
            ctx.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
