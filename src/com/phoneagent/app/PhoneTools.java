package com.phoneagent.app;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * v3.26 DSH 式工具层：原生 function schema + 富信息结果。
 * - observe 是模型之眼：模型自己决定何时看屏（不再每步强制喂图）
 * - 动作工具返回新状态摘要/失败带可恢复上下文（找不着字→返回当前可见文本列表）
 * - 权限分级沿用 ActionRisk + TaskRunner 审批流
 */
public class PhoneTools {

    public static class Exec {
        public String text;   // 给模型的工具结果文本
        public Bitmap shot;   // observe 附带的截图（harness 负责以图片消息注入）
        public boolean ok;
    }

    public static JSONArray schemas() {
        JSONArray arr = new JSONArray();
        arr.put(fn("observe", "看屏幕：返回截图和当前界面控件列表。需要眼睛时调用",
                new String[0]));
        arr.put(fn("tap_text", "按可见文字点击按钮",
                req("text"), prop("text", "string", "按钮文字，如\"设置\"")));
        arr.put(fn("tap", "按坐标点击。x/y 是 0-1000 归一化坐标（左上角 (0,0)，右下角 (1000,1000)），对着 observe 截图按比例估算即可，绝不要报像素值",
                req("x", "y"),
                prop("x", "integer", "横坐标（0-1000 归一化）"), prop("y", "integer", "纵坐标（0-1000 归一化）")));
        arr.put(fn("vision", "看屏幕回答问题：定位图标坐标、判断画面状态（坐标会以 0-1000 归一化返回，可直接给 tap 用）",
                req("question"), prop("question", "string", "如\"快门按钮的坐标是多少？\"")));
        arr.put(fn("input", "在已聚焦的输入框输入文本",
                req("text"), prop("text", "string", "要输入的文本")));
        arr.put(fn("swipe", "滑动（坐标同 tap：0-1000 归一化）",
                req("from", "to"),
                prop("from", "array", "起点[x,y]（0-1000）"), prop("to", "array", "终点[x,y]（0-1000）")));
        arr.put(fn("back", "返回上一页", new String[0]));
        arr.put(fn("home", "回到桌面/主屏（关闭当前应用回到桌面）", new String[0]));
        arr.put(fn("launch", "启动应用",
                req("package"), prop("package", "string", "包名，如com.tencent.mm")));
        arr.put(fn("sleep", "等待几秒",
                req("s"), prop("s", "integer", "秒数")));
        arr.put(fn("wait_text", "等待指定文本出现",
                req("text"), prop("text", "string", "文本"),
                prop("timeout", "integer", "超时秒，默认10")));
        arr.put(fn("use_card", "调用已保存的技能卡",
                req("id"), prop("id", "string", "技能卡id")));
        arr.put(fn("termux", "内置 Termux 跑命令（查信息/算数/文件优先用它）",
                req("cmd"), prop("cmd", "string", "命令")));
        arr.put(fn("shell", "执行 root shell 命令",
                req("cmd"), prop("cmd", "string", "命令")));
        arr.put(fn("search", "联网搜索",
                req("query"), prop("query", "string", "搜索词"),
                prop("count", "integer", "结果数，默认5")));
        arr.put(fn("read_file", "读文件",
                req("path"), prop("path", "string", "路径")));
        arr.put(fn("write_file", "写文件",
                req("path", "content"),
                prop("path", "string", "路径"), prop("content", "string", "内容")));
        arr.put(fn("fetch_url", "抓网页正文",
                req("url"), prop("url", "string", "网址")));
        arr.put(fn("remember", "记住用户信息，下次直接用",
                req("key", "value"),
                prop("key", "string", "键"), prop("value", "string", "值")));
        arr.put(fn("vd_start", "开启虚拟屏：后续操作在虚拟屏里进行，不打扰主屏。"
                + "任务耗时长、或用户可能正在用手机时用；涉及主屏当前状态的任务不要用", new String[0]));
        arr.put(fn("vd_stop", "关闭虚拟屏，回到主屏操作", new String[0]));
        return arr;
    }

    /** 工具调用 → TaskRunner step JSON。observe 不走 TaskRunner。 */
    public static String toolToStep(String name, String argsJson) {
        if ("observe".equals(name)) return null;
        try {
            JSONObject args = new JSONObject(argsJson == null ? "{}" : argsJson);
            JSONObject step = new JSONObject();
            step.put("do", name);
            for (String k : new String[]{"text", "x", "y", "question", "from", "to",
                    "package", "s", "timeout", "id", "cmd", "query", "count",
                    "path", "content", "url", "key", "value"}) {
                if (args.has(k)) step.put(k, args.get(k));
            }
            return step.toString();
        } catch (Exception e) {
            return "{\"do\":\"" + name + "\"}";
        }
    }

    /**
     * 执行工具并返回富结果。TaskRunner 由调用方（AgentSession）配置好传入。
     */
    /** 当前会话通道（AgentLoop 每次任务开始时设置）：vd 仅 app 可用 */
    public static volatile String sessionChannel = "";

    public static Exec execute(Context ctx, TaskRunner runner, String name,
                               String argsJson, boolean vdMode) {
        Exec e = new Exec();
        try {
            if ("vd_start".equals(name)) {
                // v3.36：虚拟屏仅 App 通道可用，TG/远控直接在主屏执行
                if ("tg".equals(sessionChannel) || "remote".equals(sessionChannel)) {
                    e.ok = false;
                    e.text = "当前通道（TG/远控）不使用虚拟屏，直接在主屏执行任务即可。";
                    return e;
                }
                // 动态虚拟屏：用户在设置里关闭虚拟屏功能则拒绝
                boolean allowed = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                        .getBoolean("task_vd_default", true);
                if (!allowed) {
                    e.ok = false;
                    e.text = "虚拟屏功能已在设置中关闭，直接在主屏操作。";
                    return e;
                }
                if (VirtualDisplayManager.isActive()) {
                    e.ok = true;
                    e.text = "虚拟屏已在运行（displayId="
                            + VirtualDisplayManager.getDisplayId() + "）。";
                    return e;
                }
                int did = VirtualDisplayManager.create(ctx, 720, 1280, 320);
                if (did < 0) {
                    e.ok = false;
                    e.text = "虚拟屏创建失败，直接在主屏操作。";
                    return e;
                }
                runner.setVdMode(true);
                e.ok = true;
                e.text = "🖥 虚拟屏已开启（displayId=" + did
                        + "）。后续操作都在虚拟屏里进行，主屏不受打扰；"
                        + "想看虚拟屏画面调 observe，用完记得 vd_stop。";
                return e;
            }
            if ("vd_stop".equals(name)) {
                runner.setVdMode(false);
                try {
                    VirtualDisplayManager.destroy();
                } catch (Exception ignored) {
                }
                e.ok = true;
                e.text = "虚拟屏已关闭，回到主屏操作。";
                return e;
            }
            if ("observe".equals(name)) {
                Bitmap shot = null;
                String desc;
                if (vdMode) {
                    // 虚拟屏：截图源是 VirtualDisplayManager（带重试，刚建屏可能暂无帧）
                    for (int i = 0; i < 5 && shot == null; i++) {
                        shot = VirtualDisplayManager.screenshot();
                        if (shot == null) {
                            try { Thread.sleep(600); } catch (Exception ignored) {}
                        }
                    }
                    desc = shot != null ? "（虚拟屏截图见附图）"
                            : "（虚拟屏暂无画面，可能目标应用还没启动，先 launch）";
                } else {
                    List<AgentAccessibilityService.NodeInfo> nodes =
                            AgentAccessibilityService.dumpScreen();
                    try {
                        shot = ScreenshotUtil.capture(ctx);
                    } catch (Exception ignored) {
                    }
                    desc = nodes.isEmpty()
                            ? "（读不到控件树，靠截图判断）"
                            : "控件列表：\n" + compactList(nodes);
                }
                e.ok = shot != null || !desc.contains("暂无画面");
                e.text = desc + (shot == null ? "\n截图为空" : "");
                e.shot = shot;
                return e;
            }
            // v3.38：坐标全链路 0-1000 归一化 → 像素。模型看的是 720p 缩放图，
            // 直报像素必然系统性偏移，这里按当前屏（虚拟屏/主屏）真实尺寸换算
            if ("tap".equals(name) || "swipe".equals(name)) {
                try {
                    JSONObject a = new JSONObject(
                            argsJson == null ? "{}" : argsJson);
                    int[] dim = screenDim(ctx, vdMode);
                    if ("tap".equals(name)) {
                        a.put("x", normToPx(a.optDouble("x", 0), dim[0]));
                        a.put("y", normToPx(a.optDouble("y", 0), dim[1]));
                    } else {
                        JSONArray f = a.optJSONArray("from");
                        JSONArray t = a.optJSONArray("to");
                        if (f != null && f.length() >= 2) {
                            f.put(0, normToPx(f.optDouble(0), dim[0]));
                            f.put(1, normToPx(f.optDouble(1), dim[1]));
                        }
                        if (t != null && t.length() >= 2) {
                            t.put(0, normToPx(t.optDouble(0), dim[0]));
                            t.put(1, normToPx(t.optDouble(1), dim[1]));
                        }
                    }
                    argsJson = a.toString();
                } catch (Exception ignored) {
                }
            }
            String stepJson = toolToStep(name, argsJson);
            if (stepJson == null) {
                e.ok = false;
                e.text = "未知工具：" + name;
                return e;
            }
            boolean ok = runner.execOneStep(stepJson);
            String out = runner.getLastOutput();
            e.ok = ok;
            StringBuilder sb = new StringBuilder(ok ? "成功" : "失败");
            if (out != null && !out.isEmpty()) sb.append("：").append(trim(out, "use_card".equals(name) ? 2600 : 600));
            // DSH 式富上下文：失败带恢复信息，成功带新状态摘要
            String extra = enrich(ctx, name, argsJson, ok, vdMode);
            if (!extra.isEmpty()) sb.append("\n").append(extra);
            e.text = sb.toString();
            return e;
        } catch (Exception ex) {
            e.ok = false;
            e.text = "执行异常：" + ex.getMessage();
            return e;
        }
    }

    /** 0-1000 归一化 → 像素；入参 >1000 视为已是像素，钳制即可（兼容两种回答） */
    private static int normToPx(double v, int dim) {
        if (v > 1000) return Math.max(0, Math.min((int) v, dim - 1));
        return Math.max(0, Math.min((int) (v * dim / 1000.0), dim - 1));
    }

    /** 当前操作屏的真实像素尺寸（虚拟屏固定 720x1280，主屏取真实分辨率） */
    private static int[] screenDim(Context ctx, boolean vdMode) {
        if (vdMode && VirtualDisplayManager.isActive()) return new int[]{720, 1280};
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point pt = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(pt);
            return new int[]{pt.x, pt.y};
        } catch (Exception e) {
            return new int[]{1080, 1920};
        }
    }

    /** 失败→可恢复上下文；成功→新状态摘要 */
    private static String enrich(Context ctx, String name, String argsJson,
                                 boolean ok, boolean vdMode) {
        try {
            if (vdMode) {
                return ok ? "（虚拟屏：用 observe 看新画面确认）" : "（虚拟屏：用 observe 看当前画面）";
            }
            List<AgentAccessibilityService.NodeInfo> nodes =
                    AgentAccessibilityService.dumpScreen();
            if (!ok && "tap_text".equals(name)) {
                String want;
                try {
                    want = new JSONObject(argsJson).optString("text", "");
                } catch (Exception ex) {
                    want = "";
                }
                return "没找到文字「" + want + "」。当前可见文本：\n" + compactList(nodes);
            }
            if (!ok && "launch".equals(name)) {
                return "启动失败：检查包名是否正确、应用是否已安装。";
            }
            if (ok && ("tap_text".equals(name) || "tap".equals(name)
                    || "back".equals(name) || "home".equals(name)
                    || "swipe".equals(name) || "launch".equals(name))) {
                String s = compactList(nodes);
                return s.isEmpty() ? "" : "新界面：\n" + s;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 控件列表压成紧凑文本（最多 25 条） */
    private static String compactList(List<AgentAccessibilityService.NodeInfo> nodes) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (AgentAccessibilityService.NodeInfo nd : nodes) {
            String t = nd.text == null ? "" : nd.text.trim();
            if (t.isEmpty() || t.length() > 24) continue;
            sb.append("- ").append(t);
            if (nd.clickable) sb.append(" [可点]");
            sb.append("\n");
            if (++n >= 25) break;
        }
        return sb.toString().trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---- schema 构造小工具 ----
    private static JSONObject fn(String name, String desc, String[] required, JSONObject... props) {
        try {
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("description", desc);
            JSONObject p = new JSONObject();
            p.put("type", "object");
            JSONObject pr = new JSONObject();
            for (JSONObject o : props) pr.put(o.getString("_k"), o);
            p.put("properties", pr);
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            p.put("required", r);
            f.put("parameters", p);
            JSONObject w = new JSONObject();
            w.put("type", "function");
            w.put("function", f);
            return w;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject prop(String k, String type, String desc) {
        try {
            JSONObject o = new JSONObject();
            o.put("_k", k);
            o.put("type", type);
            o.put("description", desc);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String[] req(String... keys) { return keys; }
}
