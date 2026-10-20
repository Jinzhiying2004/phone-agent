package com.phoneagent.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

/**
 * v2.60：虚拟屏管理器——后台自动化的核心。
 * 创建 OWN_CONTENT_ONLY 虚拟屏（无需特殊权限），配合 root 的
 * am start --display 启动 App、VdInput 注入点击，实现不打扰主屏的任务执行。
 */
public class VirtualDisplayManager {

    private static final String TAG = "VDManager";
    private static VirtualDisplay vd;
    private static ImageReader reader;
    // 最后一帧缓存（预览用）：虚拟屏静止时不产生新帧，预览若直接 acquire 会永远拿到空
    private static Bitmap lastFrame;
    private static long lastFrameVer = 0;
    private static long lastFrameAt = 0;
    private static int displayId = -1;

    /** 创建虚拟屏，返回 displayId（失败返回 -1） */
    public static synchronized int create(Context ctx, int w, int h, int dpi) {
        destroy(); // 先清旧的
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            DisplayManager dm = (DisplayManager) ctx.getSystemService(
                    Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("agent_vd",
                    w, h, dpi, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            displayId = vd.getDisplay().getDisplayId();
            appCtx = ctx.getApplicationContext();
            if (previewEnabled) startPreview();
            Log.i(TAG, "虚拟屏创建成功 displayId=" + displayId);
            return displayId;
        } catch (Exception e) {
            Log.e(TAG, "虚拟屏创建失败: " + e.getMessage());
            destroy();
            return -1;
        }
    }

    /** 销毁虚拟屏 */
    public static synchronized void destroy() {
        stopPreview();
        try {
            if (vd != null) { vd.release(); vd = null; }
        } catch (Exception ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Exception ignored) {}
        displayId = -1;
        if (lastFrame != null) {
            try { lastFrame.recycle(); } catch (Exception ignored) {}
            lastFrame = null;
        }
    }

    /** 预览用：取最后一帧的副本（无帧返回 null）；version 与上次不同说明画面更新过 */
    public static synchronized Bitmap peekLastFrame() {
        if (lastFrame == null) return null;
        try {
            return lastFrame.copy(lastFrame.getConfig(), false);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized long lastFrameVersion() {
        return lastFrameVer;
    }

    public static synchronized long lastFrameAgeMs() {
        return lastFrameAt == 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() - lastFrameAt;
    }

    public static synchronized boolean isActive() {
        return vd != null && displayId >= 0;
    }

    public static synchronized int getDisplayId() {
        return displayId;
    }

    /** 从虚拟屏取一帧，返回 Bitmap（失败返回 null） */
    public static synchronized Bitmap screenshot() {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            Bitmap bmp = imageToBitmap(img);
            try {
                Bitmap small = Bitmap.createScaledBitmap(bmp, 360, 640, true);
                if (lastFrame != null) lastFrame.recycle();
                lastFrame = small;
                lastFrameVer++;
                lastFrameAt = System.currentTimeMillis();
            } catch (Exception ignored) {}
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "虚拟屏截屏失败: " + e.getMessage());
            return null;
        } finally {
            if (img != null) { try { img.close(); } catch (Exception ignored) {} }
        }
    }

    private static Bitmap imageToBitmap(Image img) {
        Image.Plane[] planes = img.getPlanes();
        java.nio.ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * img.getWidth();
        Bitmap bmp = Bitmap.createBitmap(
                img.getWidth() + rowPadding / pixelStride,
                img.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        return Bitmap.createBitmap(bmp, 0, 0, img.getWidth(), img.getHeight());
    }

    // ---- v3.36 悬浮预览：App 通道任务时把虚拟屏画面悬浮显示在主屏右上角 ----
    private static volatile boolean previewEnabled = false;
    private static Context appCtx;
    private static android.widget.ImageView previewView;
    private static volatile boolean previewRunning = false;

    public static void setPreviewEnabled(boolean v) {
        previewEnabled = v;
        if (!v) stopPreview();
    }

    private static void startPreview() {
        if (appCtx == null) return;
        try {
            if (!FloatWindow.hasPermission(appCtx)) return;
        } catch (Exception e) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (previewView != null || !isActive()) return;
                android.view.WindowManager wm = (android.view.WindowManager)
                        appCtx.getSystemService(Context.WINDOW_SERVICE);
                android.util.DisplayMetrics dm =
                        appCtx.getResources().getDisplayMetrics();
                int w = dm.widthPixels / 3;
                int h = w * 16 / 9;
                final android.widget.ImageView iv =
                        new android.widget.ImageView(appCtx);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                iv.setBackgroundColor(0xFF222222);
                android.view.WindowManager.LayoutParams lp =
                        new android.view.WindowManager.LayoutParams(
                                w, h,
                                android.view.WindowManager.LayoutParams
                                        .TYPE_APPLICATION_OVERLAY,
                                android.view.WindowManager.LayoutParams
                                        .FLAG_NOT_FOCUSABLE
                                        | android.view.WindowManager.LayoutParams
                                        .FLAG_NOT_TOUCHABLE,
                                PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.END;
                lp.x = 12;
                lp.y = 110;
                wm.addView(iv, lp);
                previewView = iv;
                previewRunning = true;
                new Thread(() -> {
                    long shownVer = -1;
                    while (previewRunning && isActive()) {
                        // 缓存太旧（>2.5s 没人截图更新）时主动抓一帧刷新缓存
                        if (lastFrameAgeMs() > 2500) {
                            Bitmap t = screenshot();
                            if (t != null) t.recycle();
                        }
                        long ver = lastFrameVersion();
                        Bitmap b = ver != shownVer ? peekLastFrame() : null;
                        if (b != null) {
                            shownVer = ver;
                            new android.os.Handler(
                                    android.os.Looper.getMainLooper())
                                    .post(() -> {
                                        try {
                                            if (previewView == iv) {
                                                android.graphics.drawable
                                                        .Drawable old =
                                                        iv.getDrawable();
                                                iv.setImageBitmap(b);
                                                if (old instanceof android
                                                        .graphics.drawable
                                                        .BitmapDrawable) {
                                                    Bitmap ob = ((android
                                                            .graphics.drawable
                                                            .BitmapDrawable) old)
                                                            .getBitmap();
                                                    if (ob != null && ob != b) {
                                                        ob.recycle();
                                                    }
                                                }
                                            } else {
                                                b.recycle();
                                            }
                                        } catch (Exception ignored) {}
                                    });
                        }
                        try {
                            Thread.sleep(700);
                        } catch (Exception ignored) {}
                    }
                }, "vd-preview").start();
            } catch (Exception ignored) {}
        });
    }

    private static void stopPreview() {
        previewRunning = false;
        if (appCtx == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (previewView != null) {
                    android.view.WindowManager wm = (android.view.WindowManager)
                            appCtx.getSystemService(Context.WINDOW_SERVICE);
                    wm.removeView(previewView);
                    previewView = null;
                }
            } catch (Exception ignored) {}
        });
    }
}
