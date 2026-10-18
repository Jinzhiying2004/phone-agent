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
        try {
            if (vd != null) { vd.release(); vd = null; }
        } catch (Exception ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Exception ignored) {}
        displayId = -1;
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
            return imageToBitmap(img);
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
}
