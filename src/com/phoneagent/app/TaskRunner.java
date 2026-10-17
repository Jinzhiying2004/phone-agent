package com.phoneagent.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/** 任务执行器：解析 JSON 任务并本地执行。运行期零 token、零联网。 */
public class TaskRunner {

    public interface Listener {
        void onLog(String msg);
    }

    /**
     * 高风险动作审批人：ASK 策略的动作执行前调用，返回 true=放行。
     * 本地 UI 用弹窗向用户确认；无审批人时：远程任务默认拦截，
     * 本地受信任务（定时任务）记录警告后放行。
     */
    public interface Approver {
        boolean approve(String action, String detail);
    }

    private final Context ctx;
    private final Listener listener;
    private volatile boolean stopped = false;
    private String lastOutput = "";
    private Approver approver = null;
    private boolean remote = false;
    private ActionRisk.Policy riskPolicy = null;

    public TaskRunner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.riskPolicy = new ActionRisk.Policy(this.ctx);
    }

    /** 设置审批人（本地 UI 传入弹窗确认实现） */
    public void setApprover(Approver approver) {
        this.approver = approver;
    }

    /** 标记是否为远程（HTTP / Telegram）进来的任务：无审批人时 ASK 策略直接拦截 */
    public void setRemote(boolean remote) {
        this.remote = remote;
    }

    public void setRiskPolicy(ActionRisk.Policy policy) {
        if (policy != null) this.riskPolicy = policy;
    }

    /** 上一步动作的输出（shell/读文件/抓网页用，agent 回传给大脑） */
    public String getLastOutput() {
        return lastOutput;
    }

    public void stop() {
        stopped = true;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    /** root 命令：走常驻 RootShell 桥（复用 su 会话，不再每次 spawn） */
    /** 执行 root 命令，返回输出；失败返回 __ERR__+原因 */
    private String rootCmdOut(String cmd) {
        try {
            String[] r = RootShell.get().exec(cmd, 30);
            if (!"0".equals(r[0])) return "__ERR__exit=" + r[0] + " " + r[1].trim();
            return r[1].trim();
        } catch (Exception e) {
            return "__ERR__" + e.getMessage();
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private boolean rootCmd(String cmd) {
        try {
            String[] r = RootShell.get().exec(cmd, 30);
            String out = r[1].trim();
            if (!out.isEmpty()) {
                log("root 输出: " + (out.length() > 200 ? out.substring(0, 200) + "..." : out));
            }
            return "0".equals(r[0]);
        } catch (Exception e) {
            log("root 执行异常: " + e.getMessage());
            return false;
        }
    }

    public static String loadAsset(Context ctx, String name) {
        try {
            InputStream is = ctx.getAssets().open("tasks/" + name);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供 HTTP API 调用的单步执行 */
    public boolean execOneStep(String stepJson) {
        try {
            JSONObject st = new JSONObject(stepJson);
            log("API 单步: " + st.optString("do"));
            return runStep(st, null, 0);
        } catch (Exception e) {
            log("单步异常: " + e.getMessage());
            return false;
        }
    }

    public boolean run(String taskJson) {
        try {
            JSONObject task = new JSONObject(taskJson);
            JSONObject params = task.optJSONObject("params");
            return runTask(task, params, 0);
        } catch (Exception e) {
            log("任务异常: " + e.getMessage());
            return false;
        }
    }

    /** 递归执行任务；depth 防子技能无限嵌套 */
    private boolean runTask(JSONObject task, JSONObject params, int depth)
            throws Exception {
        if (depth > 5) {
            log("技能嵌套过深，已停止");
            return false;
        }
        JSONArray steps = task.getJSONArray("steps");
        log("开始任务：" + task.optString("name", "未命名")
                + (depth > 0 ? "（子技能 depth=" + depth + "）" : ""));
        for (int i = 0; i < steps.length(); i++) {
            if (stopped) {
                log("已停止");
                return false;
            }
            JSONObject st = substitute(steps.getJSONObject(i), params);
            String do_ = st.getString("do");
            log("步骤 " + (i + 1) + "/" + steps.length() + ": " + do_);
            boolean ok = runStep(st, params, depth);
            if (!ok && !st.optBoolean("optional", false)) {
                log("任务中断于步骤 " + (i + 1));
                return false;
            }
            Thread.sleep((long) (st.optDouble("after", 0.8) * 1000));
        }
        log("任务完成 ✓");
        return true;
    }

    /**
     * 变量替换：步骤中所有字符串里的 {{key}} 用 params 的值替换。
     * 纯逻辑，可独立测试。
     */
    static JSONObject substitute(JSONObject st, JSONObject params) throws Exception {
        if (params == null) return st;
        JSONObject out = new JSONObject();
        java.util.Iterator<String> keys = st.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, substituteValue(st.get(k), params));
        }
        return out;
    }

    private static Object substituteValue(Object v, JSONObject params) throws Exception {
        if (v instanceof String) {
            String s = (String) v;
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object pv = params.opt(k);
                if (pv != null) {
                    s = s.replace("{{" + k + "}}", String.valueOf(pv));
                }
            }
            return s;
        } else if (v instanceof JSONObject) {
            JSONObject jo = (JSONObject) v;
            JSONObject out = new JSONObject();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, substituteValue(jo.get(k), params));
            }
            return out;
        } else if (v instanceof JSONArray) {
            JSONArray ja = (JSONArray) v;
            JSONArray out = new JSONArray();
            for (int i = 0; i < ja.length(); i++) {
                out.put(substituteValue(ja.get(i), params));
            }
            return out;
        }
        return v;
    }

    private AgentAccessibilityService.NodeInfo waitText(String kw, int timeoutSec)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end) {
            if (stopped) return null;
            List<AgentAccessibilityService.NodeInfo> items =
                    AgentAccessibilityService.dumpScreen();
            AgentAccessibilityService.NodeInfo hit =
                    AgentAccessibilityService.find(items, kw);
            if (hit != null) return hit;
            Thread.sleep(1000);
        }
        return null;
    }

    /**
     * 风险门：按 ActionRisk 编译期分级查用户策略。
     * ALLOW 放行；DENY 拒绝；ASK 需要审批人确认——无审批人时远程任务拦截、
     * 本地受信任务记警告放行。返回 true=允许执行。
     */
    private boolean riskGate(String do_, JSONObject st) {
        ActionRisk.Level lv = ActionRisk.levelFor(do_);
        int pol = riskPolicy.get(lv);
        if (pol == ActionRisk.ALLOW) return true;
        if (pol == ActionRisk.DENY) {
            log("⛔ 动作被策略拒绝：" + do_ + "（" + lv.label + "）");
            return false;
        }
        // ASK
        String detail = truncate(st.toString(), 160);
        if (approver != null) {
            boolean ok = approver.approve(do_ + "（" + lv.label + "）", detail);
            if (!ok) log("⛔ 已拒绝高风险动作：" + do_);
            return ok;
        }
        if (remote) {
            log("⛔ 远程任务无审批人，拦截" + lv.label + "动作：" + do_);
            return false;
        }
        log("⚠️ " + lv.label + "动作无审批人（定时任务），按本地信任放行：" + do_);
        return true;
    }

    private boolean runStep(JSONObject st, JSONObject params, int depth) throws Exception {
        String do_ = st.getString("do");
        // ---- 风险门：编译期分级 + 用户三档策略（策略在代码里，不在 prompt 里） ----
        if (!riskGate(do_, st)) return false;
        switch (do_) {
            case "launch": {
                String pkg = st.getString("package");
                log("启动 " + pkg);
                PackageManager pm = ctx.getPackageManager();
                Intent it = pm.getLaunchIntentForPackage(pkg);
                if (it == null) {
                    log("找不到启动项: " + pkg);
                    return false;
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return true;
            }
            case "tap": {
                int x = st.getInt("x"), y = st.getInt("y");
                log("点击 (" + x + "," + y + ")");
                if (AgentAccessibilityService.tapAt(x, y)) return true;
                log("手势失败，尝试 root input");
                return rootCmd("input tap " + x + " " + y);
            }
            case "tap_text": {
                String kw = st.getString("text");
                int to = st.optInt("timeout", 10);
                log("等待并点击: " + kw);
                AgentAccessibilityService.NodeInfo hit = waitText(kw, to);
                if (hit == null) {
                    log("超时未找到: " + kw
                            + "；提示：文本不在控件树时别猜坐标乱点，"
                            + "用 vision 按截图问出坐标再 tap");
                    return false;
                }
                if (AgentAccessibilityService.clickNode(hit)) return true;
                log("节点点击失败，改点坐标 (" + hit.cx + "," + hit.cy + ")");
                if (AgentAccessibilityService.tapAt(hit.cx, hit.cy)) return true;
                return rootCmd("input tap " + hit.cx + " " + hit.cy);
            }
            case "wait_text": {
                String kw = st.getString("text");
                int to = st.optInt("timeout", 15);
                log("等待文本: " + kw);
                AgentAccessibilityService.NodeInfo hit = waitText(kw, to);
                if (hit == null) {
                    log("超时: " + kw);
                    return false;
                }
                return true;
            }
            case "assert_text": {
                String kw = st.getString("text");
                List<AgentAccessibilityService.NodeInfo> items =
                        AgentAccessibilityService.dumpScreen();
                boolean ok = AgentAccessibilityService.find(items, kw) != null;
                log("断言 " + kw + ": " + (ok ? "通过" : "失败"));
                return ok;
            }
            case "input": {
                String s = st.getString("text");
                log("输入: " + s);
                if (AgentAccessibilityService.setText(s)) return true;
                log("无障碍输入失败，尝试 root（仅英文）");
                return rootCmd("input text " + s.replace(" ", "%s").replace("'", ""));
            }
            case "swipe": {
                JSONArray f = st.getJSONArray("from"), t = st.getJSONArray("to");
                int ms = st.optInt("ms", 400);
                log("滑动");
                if (AgentAccessibilityService.swipe(
                        f.getInt(0), f.getInt(1), t.getInt(0), t.getInt(1), ms)) return true;
                return rootCmd("input swipe " + f.getInt(0) + " " + f.getInt(1)
                        + " " + t.getInt(0) + " " + t.getInt(1) + " " + ms);
            }
            case "sleep": {
                Thread.sleep(st.optInt("s", 2) * 1000L);
                return true;
            }
            case "back": {
                if (AgentAccessibilityService.goBack()) return true;
                return rootCmd("input keyevent 4");
            }
            case "shell": {
                String cmd = st.getString("cmd");
                log("执行: " + cmd);
                String out = rootCmdOut(cmd);
                lastOutput = truncate(out, 1500);
                if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
                return !out.startsWith("__ERR__");
            }
            case "termux": {
                // 内置 Termux 环境执行命令（优先于 UI 操作）
                String cmd = st.getString("cmd");
                try {
                    BootstrapManager bm = new BootstrapManager(ctx);
                    // 免 root：直接用 staging 目录（官方 Termux 不需要 root）
                    if (!bm.isInstalled()) {
                        log("Termux 环境未安装，先在终端页下载安装");
                        return false;
                    }
                    String prefix = bm.stagingDir().getAbsolutePath();
                    String bash = prefix + "/bin/bash";
                    java.io.File bashFile = new java.io.File(bash);
                    if (!bashFile.exists()) {
                        log("找不到 Termux bash: " + bash);
                        return false;
                    }
                    // 确保可执行
                    if (!bashFile.canExecute()) {
                        bashFile.setExecutable(true);
                    }
                    log("termux 执行: " + cmd);
                    ProcessBuilder pb = new ProcessBuilder(bash, "-c", cmd);
                    // 用干净环境：只保留 Termux 需要的变量，避免 App 环境干扰
                    pb.environment().clear();
                    pb.environment().putAll(bm.termuxEnv());
                    pb.redirectErrorStream(true);
                    Process pr = pb.start();
                    // 最多等 60 秒
                    boolean done = pr.waitFor(60,
                            java.util.concurrent.TimeUnit.SECONDS);
                    if (!done) {
                        pr.destroyForcibly();
                        log("termux 执行超时（60s）");
                        return false;
                    }
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    pr.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 6000) break;
                        sb.append(line).append("\n");
                    }
                    br.close();
                    int code = pr.exitValue();
                    lastOutput = truncate(sb.toString().trim(), 1500);
                    if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    return code == 0;
                } catch (Exception e) {
                    log("termux 执行失败: " + e.getMessage());
                    return false;
                }
            }
            case "search": {
                // 联网搜索（DuckDuckGo，无需 key），结果可存变量供后续步骤用
                String query = st.getString("query");
                int count = st.optInt("count", 5);
                if (count < 1) count = 1;
                if (count > 10) count = 10;
                try {
                    String url = "https://html.duckduckgo.com/html/?q="
                            + java.net.URLEncoder.encode(query, "UTF-8");
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection)
                                    new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    c.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (html.length() > 200000) break;
                        html.append(line).append("\n");
                    }
                    br.close();
                    String h = html.toString();
                    // 解析结果：标题 + 链接 + 摘要
                    java.util.List<String> titles = new java.util.ArrayList<>();
                    java.util.List<String> links = new java.util.ArrayList<>();
                    java.util.List<String> snips = new java.util.ArrayList<>();
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile(
                                    "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                                    .matcher(h);
                    while (m.find() && titles.size() < count) {
                        String href = m.group(1);
                        String title = m.group(2)
                                .replaceAll("<[^>]+>", "").trim();
                        // DuckDuckGo 跳转链接里藏着真实 url
                        String real = href;
                        int uIdx = href.indexOf("udg=");
                        if (uIdx >= 0) {
                            try {
                                real = java.net.URLDecoder.decode(
                                        href.substring(uIdx + 4).split("&")[0],
                                        "UTF-8");
                            } catch (Exception ignored) {
                            }
                        }
                        titles.add(title);
                        links.add(real);
                    }
                    java.util.regex.Matcher sm =
                            java.util.regex.Pattern.compile(
                                    "class=\"result__snippet\"[^>]*>(.*?)</a>")
                                    .matcher(h);
                    while (sm.find() && snips.size() < count) {
                        snips.add(sm.group(1)
                                .replaceAll("<[^>]+>", "").trim());
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("搜索「").append(query).append("」共 ")
                            .append(titles.size()).append(" 条结果：\n");
                    for (int i = 0; i < titles.size(); i++) {
                        sb.append("\n").append(i + 1).append(". ")
                                .append(titles.get(i)).append("\n");
                        sb.append("   ").append(links.get(i)).append("\n");
                        if (i < snips.size() && !snips.get(i).isEmpty())
                            sb.append("   ").append(snips.get(i)).append("\n");
                    }
                    lastOutput = truncate(sb.toString(), 2000);
                    log(lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    return !titles.isEmpty();
                } catch (Exception e) {
                    log("搜索失败: " + e.getMessage());
                    return false;
                }
            }
            case "vision": {
                // 视觉问答：截屏发给视觉模型（看不到的按钮/判断前后摄像头就用它）
                String question = st.optString("question",
                        "描述这张截图，指出关键可点击元素的位置");
                try {
                    // 两级截屏：无障碍 → root 兜底（部分机型无障碍截屏直接失败）
                    android.graphics.Bitmap shot =
                            ScreenshotUtil.capture(ctx);
                    if (shot == null) {
                        log("截屏失败（无障碍与 root 均不可用），无法调用视觉模型");
                        return false;
                    }
                    // 视觉模型优先级：辅助模型列表 > 主力配置的视觉模型 > 主力模型
                    String vBase = "", vKey = "", vModel = "";
                    AuxModelStore aux = new AuxModelStore(ctx);
                    AuxModelStore.AuxModel vm = aux.getVisionModel();
                    if (vm != null && !vm.apiKey.isEmpty()
                            && !vm.baseUrl.isEmpty()) {
                        vBase = vm.baseUrl;
                        vKey = vm.apiKey;
                        vModel = vm.model;
                        log("👁 用辅助模型 " + vm.name + " 看屏幕");
                    } else {
                        LlmStore ls = new LlmStore(ctx);
                        if (!ls.hasKey()) {
                            log("未配置大模型 key，辅助模型里也没有视觉模型");
                            return false;
                        }
                        vBase = ls.getBaseUrl();
                        vKey = ls.getApiKeyRaw();
                        vModel = ls.getVisionModel();
                        if (vModel.isEmpty()) vModel = ls.getModel();
                    }
                    log("👁 视觉提问: " + question);
                    LlmClient vc = new LlmClient(vBase, vKey, vModel);
                    LlmClient.Result vr = vc.chatVision(
                            question + "\n\n如果问的是位置，请用屏幕坐标"
                            + " (x, y) 回答，屏幕尺寸约 "
                            + shot.getWidth() + "x" + shot.getHeight() + "。",
                            shot, vModel);
                    lastOutput = vr.ok ? vr.text : ("视觉调用失败: " + vr.text);
                    log("👁 视觉回答: " + lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    return vr.ok;
                } catch (Exception e) {
                    log("视觉调用异常: " + e.getMessage());
                    return false;
                }
            }
            case "tap_id": {
                // 按 /screen 返回的节点 id 点击（legend 机制）
                int id = st.getInt("id");
                List<AgentAccessibilityService.NodeInfo> items =
                        AgentAccessibilityService.dumpScreen();
                if (id < 0 || id >= items.size()) {
                    log("节点 id 越界: " + id + "（共 " + items.size() + " 个）");
                    return false;
                }
                AgentAccessibilityService.NodeInfo ni = items.get(id);
                log("点击节点 #" + id + ": " + ni.text);
                if (AgentAccessibilityService.clickNode(ni)) return true;
                log("节点点击失败，改点坐标 (" + ni.cx + "," + ni.cy + ")");
                if (AgentAccessibilityService.tapAt(ni.cx, ni.cy)) return true;
                return rootCmd("input tap " + ni.cx + " " + ni.cy);
            }
            case "read_file": {
                String rp = st.getString("path");
                try {
                    java.io.File rf = new java.io.File(rp);
                    byte[] data = java.nio.file.Files.readAllBytes(rf.toPath());
                    String txt = new String(data, "UTF-8");
                    lastOutput = truncate(txt, 2000);
                    log("读文件 " + rp + "（" + data.length + " 字节）");
                    if (!lastOutput.isEmpty()) log("内容: " + lastOutput);
                    return true;
                } catch (Exception e) {
                    lastOutput = "";
                    log("读文件失败: " + e.getMessage());
                    return false;
                }
            }
            case "write_file": {
                String wp = st.getString("path");
                String content = st.optString("content", "");
                try {
                    java.io.File wf = new java.io.File(wp);
                    if (wf.getParentFile() != null) wf.getParentFile().mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(wf);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    lastOutput = "";
                    log("写文件成功: " + wp);
                    return true;
                } catch (Exception e) {
                    lastOutput = "";
                    log("写文件失败: " + e.getMessage());
                    return false;
                }
            }
            case "fetch_url": {
                String url = st.getString("url");
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "phone-agent/1.0");
                    int code = c.getResponseCode();
                    java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 8000) break;
                        sb.append(line).append('\n');
                    }
                    br.close();
                    // 去 HTML 标签，省 token
                    String txt = sb.toString().replaceAll("<script[^>]*>.*?</script>", " ")
                            .replaceAll("<style[^>]*>.*?</style>", " ")
                            .replaceAll("<[^>]+>", " ")
                            .replaceAll("\\s+", " ").trim();
                    lastOutput = truncate(txt, 2000);
                    log("抓取 " + url + " HTTP " + code);
                    if (!lastOutput.isEmpty()) log("正文: " + lastOutput);
                    return code < 400;
                } catch (Exception e) {
                    lastOutput = "";
                    log("抓取失败: " + e.getMessage());
                    return false;
                }
            }
            case "screenshot": {
                // 任务内截屏存档（无障碍优先，无需 root）
                String path = st.optString("path", "");
                android.graphics.Bitmap bmp = AgentAccessibilityService.screenshot();
                if (bmp == null) {
                    log("截屏失败");
                    return false;
                }
                try {
                    java.io.File f = path.isEmpty()
                            ? new java.io.File(ctx.getCacheDir(),
                                    "task_" + System.currentTimeMillis() + ".png")
                            : new java.io.File(path);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    bmp.recycle();
                    log("截屏已保存: " + f.getAbsolutePath());
                    return true;
                } catch (Exception e) {
                    log("截屏保存失败: " + e.getMessage());
                    return false;
                }
            }
            case "use_card": {
                // 调用技能卡：script 型跑 shell，skill 型跑步骤
                String cardId = st.getString("id");
                SkillCardStore cardStore = new SkillCardStore(ctx);
                SkillCardStore.Card card = cardStore.get(cardId);
                if (card == null) {
                    log("技能卡不存在: " + cardId);
                    return false;
                }
                cardStore.incrementUse(cardId);
                log("📇 使用技能卡：" + card.name
                        + "（" + card.typeLabel() + "·" + card.sourceLabel() + "）");
                if (SkillCardStore.TYPE_SCRIPT.equals(card.type)) {
                    String out = rootCmdOut(card.content);
                    lastOutput = truncate(out, 1500);
                    if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
                    return !out.startsWith("__ERR__");
                } else {
                    try {
                        // 兼容两种格式：{"steps":[...]} 或裸数组 [...]
                        JSONArray csteps = null;
                        String ct = card.content == null ? ""
                                : card.content.trim();
                        if (ct.startsWith("[")) {
                            csteps = new JSONArray(ct);
                        } else {
                            JSONObject cj = new JSONObject(ct);
                            csteps = cj.optJSONArray("steps");
                        }
                        if (csteps == null || csteps.length() == 0) {
                            log("技能卡步骤为空");
                            return false;
                        }
                        for (int ci = 0; ci < csteps.length(); ci++) {
                            if (stopped) return false;
                            JSONObject cs = TaskRunner.substitute(
                                    csteps.getJSONObject(ci), params);
                            if (!runStep(cs, params, depth + 1)) {
                                log("技能卡步骤失败于 " + (ci + 1));
                                return false;
                            }
                        }
                        lastOutput = "";
                        return true;
                    } catch (Exception e) {
                        log("技能卡执行异常: " + e.getMessage());
                        return false;
                    }
                }
            }
            case "skill": {
                // 子技能调用：按 name 找已启用的技能，带 params 执行
                String name = st.getString("name");
                JSONObject subParams = st.optJSONObject("params");
                // 子技能 params 继承父级未指定的键
                if (params != null) {
                    JSONObject merged = new JSONObject();
                    java.util.Iterator<String> keys = params.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        merged.put(k, params.get(k));
                    }
                    if (subParams != null) {
                        java.util.Iterator<String> sk = subParams.keys();
                        while (sk.hasNext()) {
                            String k = sk.next();
                            merged.put(k, subParams.get(k));
                        }
                    }
                    subParams = merged;
                }
                log("调用子技能: " + name);
                SkillStore.Skill skill = new SkillStore(ctx).findByName(name);
                if (skill == null || !skill.enabled) {
                    log("子技能不存在或未启用: " + name);
                    return false;
                }
                try {
                    JSONObject subTask = new JSONObject(skill.json);
                    return runTask(subTask, subParams, depth + 1);
                } catch (Exception e) {
                    log("子技能解析失败: " + e.getMessage());
                    return false;
                }
            }
            default:
                log("未知步骤: " + do_);
                return false;
        }
    }
}
