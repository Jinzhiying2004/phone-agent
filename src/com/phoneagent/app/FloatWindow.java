package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 任务进度悬浮窗：agent 运行时在屏幕上层实时显示进度。
 * 需要 SYSTEM_ALERT_WINDOW 权限，无权限时静默跳过。
 */
public class FloatWindow {

    private static android.view.View floatView;
    private static TextView floatText;
    private static WindowManager wm;
    private static FlowLightDrawable flowLight;
    /** 主界面是否在前台：在前台时不弹悬浮窗（界面里已有进度） */
    private static volatile boolean inApp = false;

    public static void setInApp(boolean v) {
        inApp = v;
        if (v) hide();
    }

    public static boolean isInApp() {
        return inApp;
    }
    private static final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
    private static final int MAX_LINES = 4;

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(ctx);
    }

    /** 跳转到悬浮窗权限设置页 */
    public static void requestPermission(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void show(Context ctx, String text) {
        Context ac = ctx.getApplicationContext();
        if (!hasPermission(ac)) return;
        // 在软件内不弹悬浮窗，进度直接看界面
        if (inApp) return;
        hide();
        try {
            wm = (WindowManager) ac.getSystemService(Context.WINDOW_SERVICE);
            LinearLayout layout = new LinearLayout(ac);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(ac, 10);
            layout.setPadding(pad, dp(ac, 8), pad, dp(ac, 8));
            // 流光边框：有流光=任务运行中
            flowLight = new FlowLightDrawable(0xE6323232, 0xFF40C4FF,
                    20, ac.getResources().getDisplayMetrics().density);
            layout.setBackground(flowLight);
            flowLight.start();

            TextView dot = new TextView(ac);
            dot.setText("🤖");
            dot.setTextSize(14);
            layout.addView(dot);

            floatText = new TextView(ac);
            floatText.setTextSize(11);
            floatText.setTextColor(0xFFFFFFFF);
            floatText.setMaxLines(MAX_LINES);
            floatText.setLineSpacing(dp(ac, 2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(ac, 240), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(ac, 6), 0, 0, 0);
            layout.addView(floatText, lp);
            lines.clear();
            pushLine(text);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(ac, 80);
            floatView = layout;
            wm.addView(floatView, params);
        } catch (Exception ignored) {
            floatView = null;
        }
    }

    public static void update(String text) {
        pushLine(text);
    }

    /** 流式追加一行，保留最近 MAX_LINES 行 */
    public static void pushLine(String text) {
        if (floatText == null) return;
        final String t = text == null ? "" : text;
        floatText.post(() -> {
            synchronized (lines) {
                lines.addLast(t);
                while (lines.size() > MAX_LINES) lines.removeFirst();
                StringBuilder sb = new StringBuilder();
                for (String l : lines) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(l);
                }
                floatText.setText(sb.toString());
            }
        });
    }

    /**
     * 本地/远控共用：从 agent 日志行提取适合悬浮窗显示的一行
     *（步骤/思考/关键动作，截断到 22 字内）。不适合显示返回 null。
     * 纯逻辑，可 JVM 单测。
     */
    public static String briefLine(String msg) {
        if (msg == null) return null;
        String line = null;
        if (msg.startsWith("🤔")) {
            line = msg.length() > 22 ? msg.substring(0, 22) + "…" : msg;
        } else if (msg.startsWith("💭")) {
            String body = msg.substring(2).trim();
            line = "💭 " + (body.length() > 20
                    ? body.substring(0, 20) + "…" : body);
        } else if (msg.startsWith("🖐")) {
            String body = msg.substring(2).trim();
            line = "🖐 " + (body.length() > 20
                    ? body.substring(0, 20) + "…" : body);
        } else if (msg.startsWith("🔍") || msg.startsWith("🔁")
                || msg.startsWith("🧠")) {
            line = msg.length() > 22 ? msg.substring(0, 22) + "…" : msg;
        }
        return line;
    }

    public static void hide() {
        try {
            if (flowLight != null) flowLight.stop();
        } catch (Exception ignored) {
        }
        flowLight = null;
        try {
            if (floatView != null && wm != null) wm.removeView(floatView);
        } catch (Exception ignored) {
        }
        floatView = null;
        floatText = null;
        wm = null;
        synchronized (lines) {
            lines.clear();
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
