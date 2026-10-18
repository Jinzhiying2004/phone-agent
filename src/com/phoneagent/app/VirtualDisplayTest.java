package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.app.ActivityOptions;
import android.util.Log;

/**
 * v2.54-spike：虚拟屏可行性验证。
 * 测试：建虚拟屏 → 把 App 启动上去 → 截到图。
 * 每一步捕获异常并返回具体信息，用于判断路是否走得通。
 */
public class VirtualDisplayTest {

    private static final String TAG = "VDTest";
    private static VirtualDisplay vd;
    private static ImageReader reader;

    /** 运行完整可行性测试，返回每步结果 */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        // Step 1: 创建 ImageReader
        try {
            reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
            sb.append("1.ImageReader OK\n");
        } catch (Exception e) {
            sb.append("1.ImageReader FAIL: ").append(e.getMessage()).append("\n");
            return sb.toString();
        }
        // Step 2: 创建虚拟屏（先试 PUBLIC，再试 OWN_CONTENT_ONLY）
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        int[] flagsToTry = {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        };
        String[] flagNames = {"PUBLIC", "OWN_CONTENT_ONLY"};
        boolean created = false;
        for (int i = 0; i < flagsToTry.length && !created; i++) {
            try {
                vd = dm.createVirtualDisplay("agent_vd_" + flagNames[i],
                        720, 1280, 160, reader.getSurface(), flagsToTry[i]);
                int did = vd.getDisplay().getDisplayId();
                sb.append("2.虚拟屏 OK flag=").append(flagNames[i])
                        .append(" displayId=").append(did).append("\n");
                created = true;
                // Step 3: 尝试把 App 启动到虚拟屏
                try {
                    Intent it = ctx.getPackageManager()
                            .getLaunchIntentForPackage("com.luna.music");
                    if (it == null) {
                        sb.append("3.找不到测试 App\n");
                    } else {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ActivityOptions opts = ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(did);
                        ctx.startActivity(it, opts.toBundle());
                        sb.append("3.启动 App 到虚拟屏 OK\n");
                    }
                } catch (Exception e3) {
                    sb.append("3.启动 App FAIL: ")
                            .append(e3.getClass().getSimpleName())
                            .append(": ").append(e3.getMessage()).append("\n");
                }
                // Step 4: 尝试从 ImageReader 取一帧
                try {
                    Thread.sleep(3000); // 等 App 启动
                    android.media.Image img = reader.acquireLatestImage();
                    if (img != null) {
                        sb.append("4.截到虚拟屏图像 OK ")
                                .append(img.getWidth()).append("x")
                                .append(img.getHeight()).append("\n");
                        img.close();
                    } else {
                        sb.append("4.未取到图像（null，可能无内容渲染）\n");
                    }
                } catch (Exception e4) {
                    sb.append("4.取图 FAIL: ").append(e4.getMessage()).append("\n");
                }
            } catch (Exception e2) {
                sb.append("2.虚拟屏 FAIL flag=").append(flagNames[i])
                        .append(": ").append(e2.getClass().getSimpleName())
                        .append(": ").append(e2.getMessage()).append("\n");
            }
        }
        if (!created) {
            sb.append("结论：建不出虚拟屏，此路不通\n");
        }
        Log.i(TAG, sb.toString());
        return sb.toString();
    }

    /** 释放虚拟屏 */
    public static void release() {
        try {
            if (vd != null) { vd.release(); vd = null; }
        } catch (Exception ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Exception ignored) {}
    }
}
