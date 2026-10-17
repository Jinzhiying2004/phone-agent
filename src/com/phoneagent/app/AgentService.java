package com.phoneagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：保活 HTTP 控制服务。端口可在 App 内修改（默认 28789），token 鉴权。 */
public class AgentService extends Service {

    /** 默认端口（v2.41 起由 18789 改为 28789） */
    public static final int DEFAULT_PORT = 28789;

    /** 当前生效端口（用户可在远控页修改） */
    public static int getPort(Context ctx) {
        return ctx.getSharedPreferences("svc", Context.MODE_PRIVATE)
                .getInt("port", DEFAULT_PORT);
    }

    public static void setPort(Context ctx, int port) {
        ctx.getSharedPreferences("svc", Context.MODE_PRIVATE)
                .edit().putInt("port", port).apply();
    }

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private AgentHttpServer http;
    private android.os.Handler schedHandler;

    private final Runnable schedTick = new Runnable() {
        @Override
        public void run() {
            try {
                runDueSchedules();
            } catch (Exception ignored) {
            }
            if (schedHandler != null) schedHandler.postDelayed(this, 60_000);
        }
    };

    /** 本地定时任务：每天 / 每间隔执行技能，零 token */
    private void runDueSchedules() {
        ScheduleStore store = new ScheduleStore(this);
        HistoryStore hist = new HistoryStore(this);
        SkillStore skills = new SkillStore(this);
        long now = System.currentTimeMillis();
        for (ScheduleStore.Schedule sc : store.due(now)) {
            String json = null;
            String name = sc.skillName;
            for (SkillStore.Skill sk : skills.list()) {
                if (sk.id.equals(sc.skillId) && sk.enabled) {
                    json = sk.json;
                    name = sk.name;
                    break;
                }
            }
            boolean ok = false;
            if (json != null) {
                TaskRunner r = new TaskRunner(this, msg -> {
                });
                ok = r.run(json);
            }
            hist.append(name + "（定时）", ok);
            store.touch(sc.id, now);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        String chId = "agent_svc";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    chId, "远程控制服务", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, chId)
                : new Notification.Builder(this);
        int port = getPort(this);
        Notification n = nb.setContentTitle("手机Agent")
                .setContentText("远程控制服务运行中（端口 " + port + "）")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, n);
        try {
            http = new AgentHttpServer(port, new ApiHandler(this));
            http.start();
            running = true;
            // 定时任务调度循环（每分钟检查一次）
            schedHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            schedHandler.postDelayed(schedTick, 30_000);
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (schedHandler != null) schedHandler.removeCallbacks(schedTick);
        if (http != null) http.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
