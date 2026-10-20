package com.phoneagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/**
 * v3.42 常驻自启入口：开机（BOOT_COMPLETED）、覆盖安装更新
 * （MY_PACKAGE_REPLACED）、看门狗闹钟（com.phoneagent.app.WATCHDOG）
 * 都从这里拉起 AgentService，不再需要用户手动打开 App。
 * 用户在 App 里手动点「停止服务」会留下 svc_off 标记文件，
 * 所有自启路径（含 Magisk root 守护脚本）都尊重该标记，不跟用户抢。
 */
public class BootReceiver extends BroadcastReceiver {

    public static boolean userStopped(Context ctx) {
        try {
            return new File(ctx.getFilesDir(), "svc_off").exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static void setUserStopped(Context ctx, boolean stopped) {
        try {
            File f = new File(ctx.getFilesDir(), "svc_off");
            if (stopped) {
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.createNewFile();
                }
            } else if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (userStopped(ctx)) return;
        if (AgentService.isRunning()) return;
        try {
            Intent i = new Intent(ctx, AgentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }
}
