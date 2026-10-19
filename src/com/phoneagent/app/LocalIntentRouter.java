package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.provider.AlarmClock;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高频意图本地路由：这类请求意图明确、无需大模型思考，
 * 直接走本地系统能力，零 token、更快更稳（对标 Hermes 的确定性本地指令路由）。
 *
 * <p>当前覆盖（全部无需危险权限）：
 * <ul>
 * <li>设闹钟：「明天早上7点设个闹钟」「定个7:30的闹钟」→ 系统闹钟</li>
 * <li>计时器：「倒计时10分钟」「计时5分钟」→ 系统计时器</li>
 * <li>打开应用：「打开微信」「启动相机」→ 按应用名匹配启动</li>
 * <li>响铃模式：「静音」「震动」「响铃/正常模式」→ AudioManager</li>
 * </ul>
 */
public class LocalIntentRouter {

    public static class RouteResult {
        public final boolean handled;
        public final String message;

        public RouteResult(boolean handled, String message) {
            this.handled = handled;
            this.message = message;
        }
    }

    /** 尝试本地路由；不匹配返回 null（调用方继续走 agent 流程）。 */
    public static RouteResult tryRoute(Context ctx, String goal) {
        if (goal == null) return null;
        String t = goal.trim();
        RouteResult r;
        r = tryAlarm(ctx, t);
        if (r != null) return r;
        r = tryTimer(ctx, t);
        if (r != null) return r;
        r = tryOpenApp(ctx, t);
        if (r != null) return r;
        r = tryRinger(ctx, t);
        if (r != null) return r;
        return null;
    }

    // ---------------- 闹钟 ----------------

    private static final Pattern P_ALARM_KW =
            Pattern.compile("(设|定).{0,4}闹钟|闹钟|叫我起床|喊我起床");

    private static RouteResult tryAlarm(Context ctx, String t) {
        if (!P_ALARM_KW.matcher(t).find()) return null;
        int[] hm = parseAlarmTime(t);
        if (hm == null) return null; // 没解析出时间，交给 agent
        try {
            Intent it = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hm[0])
                    .putExtra(AlarmClock.EXTRA_MINUTES, hm[1])
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "phone-agent 闹钟")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true, "⏰ 已设置闹钟 "
                    + String.format("%02d:%02d", hm[0], hm[1])
                    + "（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "⏰ 闹钟设置失败：" + e.getMessage());
        }
    }

    /**
     * 解析中文时间表达，返回 [hour, minute]；解析不出返回 null。
     * 纯函数，可独立测试。
     * 支持：「7点」「7点半」「7:30」「07:30」「早上7点」「下午3点」
     * 「晚上8点半」「晚上12点(→0点)」「凌晨12点(→0点)」
     */
    static int[] parseAlarmTime(String t) {
        // 上午/下午等前缀
        String period = null;
        Matcher pm = Pattern.compile("(凌晨|早上|上午|中午|下午|晚上|夜间)")
                .matcher(t);
        if (pm.find()) period = pm.group(1);

        Matcher m = Pattern.compile("(\\d{1,2})\\s*[点:：]\\s*(\\d{1,2})?\\s*分?")
                .matcher(t);
        if (!m.find()) return null;
        int hour, min = 0;
        try {
            hour = Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return null;
        }
        String minStr = m.group(2);
        boolean half = false;
        if (minStr != null && !minStr.isEmpty()) {
            try {
                min = Integer.parseInt(minStr);
            } catch (Exception e) {
                return null;
            }
        } else {
            // 「7点半」
            int end = m.end();
            if (end < t.length() && t.charAt(end) == '半') {
                half = true;
                min = 30;
            }
        }
        if (hour < 0 || hour > 23 || min < 0 || min > 59) return null;
        // 12 小时制转换
        if (hour <= 12 && period != null) {
            boolean isPm = period.contains("下午") || period.contains("晚上")
                    || period.contains("夜间") || "中午".equals(period);
            if (isPm) {
                if (hour < 12) hour += 12;
                // 「晚上12点」= 午夜 0 点
                else if (hour == 12 && (period.contains("晚上")
                        || period.contains("凌晨") || period.contains("夜间"))) hour = 0;
            } else if (hour == 12 && period.contains("凌晨")) {
                hour = 0; // 「凌晨12点」= 午夜 0 点
            }
        }
        // 「13点」这种 24 小时制直接用
        return new int[]{hour, min};
    }

    // ---------------- 计时器 ----------------

    private static RouteResult tryTimer(Context ctx, String t) {
        Matcher m = Pattern.compile("(倒计时|计时|定时)\\s*(\\d+)\\s*分(钟)?")
                .matcher(t);
        if (!m.find()) return null;
        int minutes;
        try {
            minutes = Integer.parseInt(m.group(2));
        } catch (Exception e) {
            return null;
        }
        if (minutes <= 0 || minutes > 600) return null;
        try {
            Intent it = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "phone-agent 计时")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true, "⏱ 已开始 " + minutes
                    + " 分钟倒计时（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "⏱ 计时器启动失败：" + e.getMessage());
        }
    }

    // ---------------- 打开应用 ----------------

    private static final Pattern P_OPEN =
            Pattern.compile("^(打开|启动|开一下)\\s*(.+?)\\s*$");

    private static RouteResult tryOpenApp(Context ctx, String t) {
        Matcher m = P_OPEN.matcher(t);
        if (!m.find()) return null;
        String name = m.group(2).trim();
        if (name.isEmpty() || name.length() > 20) return null;
        String pkg = findLaunchPackage(ctx, name);
        if (pkg == null) {
            return new RouteResult(false,
                    "📱 没找到叫「" + name + "」的应用，换 agent 试试");
        }
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                return new RouteResult(false, "📱 「" + name + "」无法启动");
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true,
                    "📱 已打开「" + name + "」（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "📱 打开失败：" + e.getMessage());
        }
    }

    /** 按应用名（label 或包名片段）找可启动包；找不到返回 null。 */
    static String findLaunchPackage(Context ctx, String name) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            String contains = null;
            for (ResolveInfo ri : list) {
                String label = String.valueOf(
                        ri.loadLabel(pm)).replaceAll("\\s+", "");
                String n = name.replaceAll("\\s+", "");
                if (label.equals(n)) return ri.activityInfo.packageName;
                if (contains == null && (label.contains(n) || n.contains(label))) {
                    contains = ri.activityInfo.packageName;
                }
            }
            return contains;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 响铃模式 ----------------

    /** v2.82：判断是否为纯聊天（非任务）。打招呼/感谢/闲聊等不走任务流。 */
    private static final Pattern P_CHAT =
            Pattern.compile("^(你好|您好|hi|hello|嗨|早安|早上好|晚安|谢谢|感谢|多谢|再见|拜拜|"
                    + "你是谁|你叫什么|你好呀|hello|hey)[！!。.,，]*$",
                    Pattern.CASE_INSENSITIVE);

    /** 动作动词：出现这些词大概率是任务，不是闲聊 */
    private static final Pattern P_TASK_VERB =
            Pattern.compile("打开|启动|播放|搜索|查找|设置|调节|安装|卸载|拍照|截图|"
                    + "发|查|订|买|定|下载|上传|删除|清理|备份|同步|分享|翻译");

    public static boolean isChat(String goal) {
        if (goal == null) return false;
        String t = goal.trim();
        // 明确的打招呼/闲聊
        if (P_CHAT.matcher(t).matches()) return true;
        // 很短且不含动作动词 → 可能是闲聊
        if (t.length() <= 8 && !P_TASK_VERB.matcher(t).find()) return true;
        return false;
    }

    private static RouteResult tryRinger(Context ctx, String t) {
        int mode = -1;
        String label = null;
        if (Pattern.compile("^(.*)(静音|调成静音|开启静音)(.*)$").matcher(t).matches()
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_SILENT;
            label = "静音";
        } else if (t.matches(".*(震动模式|调成震动|开启震动).*")
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_VIBRATE;
            label = "震动";
        } else if (t.matches(".*(响铃模式|正常模式|取消静音|调成响铃).*")
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_NORMAL;
            label = "响铃";
        }
        if (mode < 0) return null;
        try {
            AudioManager am = (AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            am.setRingerMode(mode);
            return new RouteResult(true,
                    "🔔 已切换为" + label + "模式（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "🔔 切换失败：" + e.getMessage());
        }
    }
}
