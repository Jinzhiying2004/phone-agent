package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 任务运行指示：v3.40 起改为全屏流光边框（无文字胶囊）。
 * 旧胶囊压在屏幕顶部正中，挡按钮视线且会吃掉落在它范围内的点击；
 * 现在只沿屏幕边缘画一道流光，触摸全穿透——流光在转 = agent 在干活。
 * 需要 SYSTEM_ALERT_WINDOW 权限，无权限时静默跳过。
 */
public class FloatWindow {

    private static android.view.View floatView;
    private static WindowManager wm;
    private static FlowLightDrawable flowLight;
    /** 主界面是否在前台：在前台时不弹悬浮窗（界面里已有进度） */
    private static volatile boolean inApp = false;

    public static void setInApp(boolean v) {
        inApp = v;
        if (v) {
            hide();
        } else if (taskActive && taskCtx != null && floatView == null) {
            // v3.39：任务进行中离开 App——补上发起时被前台吞掉的悬浮窗
            show(taskCtx, taskTitle);
        }
    }

    // v3.39：任务级登记——通道任务统一走 begin/end，
    // 任务在 App 前台时发起也不丢窗（离开 App 自动补弹），异常收尾不残留
    private static volatile boolean taskActive = false;
    private static Context taskCtx;
    private static String taskTitle = "";

    public static void beginTask(Context ctx, String title) {
        try {
            taskCtx = ctx.getApplicationContext();
            taskTitle = title == null ? "" : title;
            taskActive = true;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> {
                        if (taskActive && !inApp && floatView == null) {
                            show(taskCtx, taskTitle);
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    public static void endTask(String text) {
        taskActive = false;
        try {
            // 无文字版：结束 1.5 秒后收起流光
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(FloatWindow::hide, 1500);
        } catch (Exception ignored) {
        }
    }

    public static boolean isInApp() {
        return inApp;
    }

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
            // v3.40：全屏流光边框——透明底、零圆角，只沿屏幕边缘流动；
            // FLAG_NOT_TOUCHABLE 触摸全穿透，不挡任何按钮（胶囊版曾吃点击）
            android.view.View glow = new android.view.View(ac);
            flowLight = new FlowLightDrawable(android.graphics.Color.TRANSPARENT,
                    0xFF40C4FF, 0,
                    ac.getResources().getDisplayMetrics().density);
            glow.setBackground(flowLight);
            flowLight.start();
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            floatView = glow;
            wm.addView(floatView, params);
        } catch (Exception ignored) {
            floatView = null;
        }
    }

    public static void update(String text) {
        pushLine(text);
    }

    /** v3.40 起无文字显示：pushLine 仅保留自愈（流光缺失时补上） */
    public static void pushLine(String text) {
        if (floatView == null && taskActive && !inApp && taskCtx != null) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> {
                        if (taskActive && !inApp && floatView == null) {
                            show(taskCtx, taskTitle);
                        }
                    });
        }
    }

    /**
     * 从 agent 日志行提取关键行（步骤/思考/关键动作）。v3.40 起悬浮层无文字，
     * 此方法保留供通道侧判断"有关键进展"用，返回值不再渲染。
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
        wm = null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
