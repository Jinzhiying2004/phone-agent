package com.phoneagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务存储（OpenClaw / Hermes cron 思想的零 token 本地版）。
 * 类型：daily（每天 HH:mm）/ interval（每 N 分钟）。由 AgentService 的
 * 调度循环每分钟检查执行，需要远程控制服务保持运行。
 */
public class ScheduleStore {

    public static class Schedule {
        public String id;
        public String skillId;
        public String skillName;
        public String type; // "daily" | "interval"
        public int hour, minute; // daily
        public int everyMin;     // interval
        public boolean enabled = true;
        public long lastRun;
    }

    private final File file;

    public ScheduleStore(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "schedules.json");
    }

    public synchronized List<Schedule> list() {
        List<Schedule> out = new ArrayList<>();
        try {
            JSONArray arr = load();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Schedule s = new Schedule();
                s.id = o.getString("id");
                s.skillId = o.optString("skillId", "");
                s.skillName = o.optString("skillName", "");
                s.type = o.optString("type", "daily");
                s.hour = o.optInt("hour", 8);
                s.minute = o.optInt("minute", 0);
                s.everyMin = o.optInt("everyMin", 60);
                s.enabled = o.optBoolean("enabled", true);
                s.lastRun = o.optLong("lastRun", 0);
                out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void add(Schedule s) {
        try {
            if (s.id == null) s.id = UUID.randomUUID().toString();
            JSONArray arr = load();
            arr.put(toJson(s));
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    public synchronized void remove(String id) {
        try {
            JSONArray arr = load();
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (!id.equals(arr.getJSONObject(i).optString("id"))) {
                    next.put(arr.getJSONObject(i));
                }
            }
            SkillStore.writeFile(file, next.toString());
        } catch (Exception ignored) {
        }
    }

    public synchronized void setEnabled(String id, boolean enabled) {
        update(id, o -> o.put("enabled", enabled));
    }

    public synchronized void touch(String id, long now) {
        update(id, o -> o.put("lastRun", now));
    }

    private interface Updater {
        void apply(JSONObject o) throws Exception;
    }

    private synchronized void update(String id, Updater u) {
        try {
            JSONArray arr = load();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (id.equals(o.optString("id"))) u.apply(o);
            }
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** 返回当前到期的定时任务 */
    public List<Schedule> due(long now) {
        List<Schedule> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(now);
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        long dayStart = d.getTimeInMillis();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);

        for (Schedule s : list()) {
            if (!s.enabled) continue;
            if ("daily".equals(s.type)) {
                boolean passed = h > s.hour || (h == s.hour && m >= s.minute);
                if (passed && s.lastRun < dayStart) out.add(s);
            } else {
                if (s.everyMin > 0 && now - s.lastRun >= s.everyMin * 60_000L) out.add(s);
            }
        }
        return out;
    }

    public String describe(Schedule s) {
        String when = "daily".equals(s.type)
                ? String.format("每天 %02d:%02d", s.hour, s.minute)
                : ("每 " + s.everyMin + " 分钟");
        return when + " → " + s.skillName;
    }

    private static JSONObject toJson(Schedule s) throws Exception {
        return new JSONObject()
                .put("id", s.id)
                .put("skillId", s.skillId)
                .put("skillName", s.skillName)
                .put("type", s.type)
                .put("hour", s.hour)
                .put("minute", s.minute)
                .put("everyMin", s.everyMin)
                .put("enabled", s.enabled)
                .put("lastRun", s.lastRun);
    }

    private JSONArray load() {
        try {
            if (file.exists()) return new JSONArray(SkillStore.readFile(file));
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }
}
