package com.phoneagent.app;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v3.31 智能沉淀·遥测层：运行事件按天落盘（JSONL），保留 30 天。
 * 事件类型：task_start / task_end / tool_call / model_flag / chat / router_miss。
 * 数据只在本机，用户主动分享沉淀报告才会离开手机。
 */
public class Telemetry {

    private static final Object LOCK = new Object();
    private static final int KEEP_DAYS = 30;

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "telemetry");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 记录一个事件（异步安全：内部同步追加一行） */
    public static void event(Context ctx, String channel, String type, JSONObject data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("ch", channel == null ? "unknown" : channel);
            o.put("type", type);
            if (data != null) {
                java.util.Iterator<String> it = data.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, data.get(k));
                }
            }
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new Date());
            File f = new File(dir(ctx), "events-" + day + ".jsonl");
            synchronized (LOCK) {
                FileWriter w = new FileWriter(f, true);
                w.write(o.toString());
                w.write("\n");
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 清理超过 30 天的事件文件 */
    public static void prune(Context ctx) {
        try {
            File[] fs = dir(ctx).listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
            for (File f : fs) {
                if (f.lastModified() < cutoff) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** 截断任意长文本（落盘控量） */
    public static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
