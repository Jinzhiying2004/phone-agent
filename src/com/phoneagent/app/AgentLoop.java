package com.phoneagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 主循环：眼睛（读屏）→ 大脑（大模型思考）→ 手（执行动作）。
 * <p>
 * 设计原则（手机端适配）：
 * <ul>
 * <li>省 token：界面用紧凑文本（编号+文字）而非截图传给模型；
 *      历史保留近 5 轮，老轮次只留动作摘要（思考截断 40 字）</li>
 * <li>安全：动作白名单，不含 shell/skill 等高危步骤；有最大步数与手动停止</li>
 * <li>零常驻：跑在调用方起的后台线程，无障碍/任务执行器都是现成的本地能力</li>
 * </ul>
 */
public class AgentLoop {

    public interface Listener {
        void onLog(String msg);

        boolean isStopped();

        /** 超时/卡住时询问用户是否继续，返回 true=继续 */
        default boolean askContinue(String reason) {
            return true;
        }
    }

    /** 循环内允许模型调用的动作（白名单） */
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "tap_text", "tap_id", "tap", "input", "swipe", "back",
            "wait_text", "sleep", "launch", "screenshot", "use_card",
            "termux", "vision"
    ));

    /** 高级动作：shell/文件/网络/搜索，需用户在界面开启 */
    private static final Set<String> ALLOWED_ADV = new HashSet<>(Arrays.asList(
            "shell", "read_file", "write_file", "fetch_url", "search"
    ));

    private static final int HISTORY_KEEP = 5;

    private boolean allowAdvanced = true;
    private String lastHistory = "";
    private String supervisorFeedback = "";
    private boolean supervisorEnabled = true;
    // ---- 工作流：todo 拆解 + 验证 + 排错 ----
    /** 当前计划步骤（todo 列表） */
    private final java.util.List<String> todoSteps = new java.util.ArrayList<>();
    private int todoIndex = 0;
    /** 连续失败计数（用于智能换方法） */
    private int failStreak = 0;
    /** 上一步的屏幕指纹（用于验证动作是否生效） */
    private String lastScreenFp = "";
    // ---- v2.45 任务规划增强 ----
    /** 结构化任务计划（子任务拆解 + 试错记录 + 检查点） */
    private TaskPlan taskPlan = new TaskPlan();
    /** 最近成功启动的应用包名（自恢复 L2 用） */
    private String lastLaunchPkg = "";
    /** 自恢复阶梯等级（1=返回 2=重进 3=回退检查点），有进展清零 */
    private int recoveryLevel = 0;
    /** 累计自恢复轮数（防无限恢复） */
    private int recoveryRounds = 0;
    private static final int MAX_RECOVERY_ROUNDS = 6;
    /** 待注入 prompt 的系统提醒（如下轮换招） */
    private String pendingNudge = "";
    private ActionRisk.Policy riskPolicy = null;
    private TaskRunner.Approver approver = null;
    private boolean remote = false;
    private final java.util.List<JSONObject> auditSteps = new java.util.ArrayList<>();

    /** 审计：每步动作明细（do/ok/输出摘要），供运行记录存档 */
    public String getAuditJson() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : auditSteps) arr.put(o);
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public void setRiskPolicy(ActionRisk.Policy p) {
        riskPolicy = p;
    }

    public void setApprover(TaskRunner.Approver a) {
        approver = a;
    }

    public void setRemote(boolean r) {
        remote = r;
    }

    public void setSupervisorEnabled(boolean v) {
        supervisorEnabled = v;
    }
    private String thinkerPlan = "";
    private java.util.function.Supplier<String> feedbackSupplier;

    public void setSupervisorFeedback(String fb) {
        supervisorFeedback = fb == null ? "" : fb;
    }

    /** 用户实时反馈供应商（每步检查，有则注入 prompt） */
    public void setFeedbackSupplier(java.util.function.Supplier<String> sup) {
        feedbackSupplier = sup;
    }

    private String conversationContext = "";

    /** 对话上下文（本次会话最近的消息，让 agent 有记忆） */
    public void setConversationContext(String ctx) {
        conversationContext = ctx == null ? "" : ctx;
    }

    /** 思考者的执行计划 */
    public void setPlan(String plan) {
        thinkerPlan = plan == null ? "" : plan;
    }

    /** 设置 todo 步骤列表（工作流拆解） */
    public void setTodoSteps(java.util.List<String> steps) {
        todoSteps.clear();
        if (steps != null) todoSteps.addAll(steps);
        todoIndex = 0;
        failStreak = 0;
    }

    /** 标记当前步骤完成，前进到下一步 */
    public void todoDone() {
        if (todoIndex < todoSteps.size()) todoIndex++;
        failStreak = 0;
    }

    /** 记录失败（连续失败多了提示换方法） */
    public void todoFail() {
        failStreak++;
    }

    /** 当前 todo 进度描述 */
    public String todoProgress() {
        if (todoSteps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n📋 任务拆解 (").append(todoIndex + 1)
                .append("/").append(todoSteps.size()).append("):\n");
        for (int i = 0; i < todoSteps.size(); i++) {
            sb.append(i < todoIndex ? "✅ " : (i == todoIndex ? "▶ " : "⏳ "))
                    .append(todoSteps.get(i)).append("\n");
        }
        if (failStreak >= 2) {
            sb.append("⚠ 已连续失败 ").append(failStreak)
                    .append(" 次，换个方法试试（比如文本点不到就用 vision 看坐标）\n");
        }
        return sb.toString();
    }

    /** 屏幕指纹：用于验证动作是否生效（简单哈希） */
    public static String screenFingerprint(String screenText) {
        if (screenText == null) return "";
        // 取关键特征：长度 + 前 200 字符的哈希
        String key = screenText.length() + ":"
                + (screenText.length() > 200
                ? screenText.substring(0, 200) : screenText);
        return Integer.toHexString(key.hashCode());
    }

    /** 验证动作是否生效：屏幕是否变化 */
    public String verifyAction(String beforeFp, String afterFp,
                               String actionDesc) {
        if (beforeFp.equals(afterFp)) {
            return "⚠ 动作未生效（屏幕无变化）：" + actionDesc
                    + "，请换方法或检查目标是否存在";
        }
        return "";
    }

    public void setAllowAdvanced(boolean v) {
        allowAdvanced = v;
    }

    public String getLastHistory() {
        return lastHistory;
    }

    private final Context ctx;
    private final LlmClient llm;
    private final Listener listener;

    public AgentLoop(Context ctx, LlmClient llm, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.llm = llm;
        this.listener = listener;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    /**
     * 在调用线程执行循环（调用方负责放后台线程）。
     *
     * @return true=模型判定完成，false=中断/失败/达最大步数
     */
    public boolean run(String goal, int maxSteps) {
        log("🎯 目标：" + goal);
        // v2.45：结构化任务计划（拆解一次，状态机推进）
        taskPlan = buildTaskPlan(goal);
        recoveryLevel = 0;
        recoveryRounds = 0;
        pendingNudge = "";
        log("📋 任务计划：" + taskPlan.size() + " 个子任务");
        StringBuilder history = new StringBuilder();
        int rounds = 0;
        // 实时监管状态
        long taskStart = System.currentTimeMillis();
        long lastProgress = taskStart;
        String lastScreen = "";
        int sameScreenCount = 0;
        java.util.ArrayDeque<String> recentActs = new java.util.ArrayDeque<>();
        final long TASK_TIMEOUT_MS = 5 * 60 * 1000;      // 任务总超时 5 分钟
        final long STALL_TIMEOUT_MS = 90 * 1000;         // 无进展 90 秒
        final int MONITOR_EVERY = 6;                      // 每 6 步 LLM 监管一次

        for (int step = 1; step <= maxSteps; step++) {
            if (listener != null && listener.isStopped()) {
                log("已手动停止");
                return false;
            }
            // 超时检查：主动询问用户
            long elapsed = System.currentTimeMillis() - taskStart;
            long idle = System.currentTimeMillis() - lastProgress;
            if (elapsed > TASK_TIMEOUT_MS || idle > STALL_TIMEOUT_MS) {
                String reason = buildTimeoutReason(goal, step, elapsed,
                        idle, history.toString());
                log("⏳ " + reason);
                boolean goOn = listener != null
                        && listener.askContinue(reason);
                if (!goOn) {
                    log("用户选择停止任务");
                    return false;
                }
                log("用户选择继续");
                // 重置计时，避免反复弹窗
                taskStart = System.currentTimeMillis();
                lastProgress = taskStart;
            }
            // 眼睛：读屏并序列化为紧凑文本
            List<AgentAccessibilityService.NodeInfo> nodes =
                    AgentAccessibilityService.dumpScreen();
            if (nodes.isEmpty()) {
                log("读屏为空（可能息屏），等待 3 秒重试");
                sleepQuiet(3000);
                nodes = AgentAccessibilityService.dumpScreen();
                if (nodes.isEmpty()) {
                    log("仍读不到界面，停止");
                    return false;
                }
            }
            String screen = serialize(nodes);

            // ---- 实时监管（零 token 本地检查） ----
            String localWarn = null;
            // 1. 界面无变化检测 → v2.45：走自恢复阶梯，不只告警
            if (screen.equals(lastScreen)) {
                sameScreenCount++;
                if (sameScreenCount >= 3) {
                    sameScreenCount = 0;
                    if (!doRecoveryLadder()) {
                        log("🛟 自恢复已达上限，停止任务");
                        return false;
                    }
                    continue; // 本轮跳过思考，下轮重新看屏验证恢复效果
                }
            } else {
                sameScreenCount = 0;
                lastScreen = screen;
                lastProgress = System.currentTimeMillis();
                recoveryLevel = 0; // 有进展，恢复阶梯清零
            }

            // 用户实时反馈：执行中指出的问题
            try {
                String userFb = feedbackSupplier != null
                        ? feedbackSupplier.get() : null;
                if (userFb != null && !userFb.trim().isEmpty()) {
                    log("💬 用户补充：" + userFb);
                    history.append("\n【用户实时补充】").append(userFb).append("\n");
                }
            } catch (Exception ignored) {
            }

            // 2. 定期 LLM 监管（每 6 步）
            String monitorAdvice = null;
            if (step % MONITOR_EVERY == 0 && step > 1 && supervisorEnabled) {
                try {
                    String recent = history.length() > 1200
                            ? history.substring(history.length() - 1200)
                            : history.toString();
                    log("🔍 监管者巡检中…");
                    monitorAdvice = Supervisor.monitor(llm, goal, recent, step);
                    if (monitorAdvice != null) log(monitorAdvice);
                } catch (Exception ignored) {
                }
            }
            if (localWarn != null) {
                log(localWarn);
                history.append("\n").append(localWarn).append("\n");
            }
            if (monitorAdvice != null) {
                history.append("\n").append(monitorAdvice).append("\n");
            }

            // 大脑：组 prompt 问模型
            String prompt = buildPrompt(goal, screen, history.toString(), step,
                    skillCardsSection(goal));
            log("🤔 第 " + step + " 步思考中...");
            LlmClient.Result r = llm.chat(prompt);
            if (!r.ok) {
                log("大脑请求失败：" + r.text);
                return false;
            }
            JSONObject decision = extractJson(r.text);
            if (decision == null) {
                log("模型回复不是合法 JSON，原文前 120 字："
                        + r.text.substring(0, Math.min(120, r.text.length())));
                return false;
            }
            String think = decision.optString("think", "");
            if (!think.isEmpty()) log("💭 " + think);
            if (decision.optBoolean("done", false)) {
                lastHistory = history.toString();
                log("✅ 模型判定任务完成");
                return true;
            }

            // 手：执行动作
            JSONArray actions = decision.optJSONArray("actions");
            if (actions == null || actions.length() == 0) {
                log("模型未给出动作，停止");
                return false;
            }
            TaskRunner runner = new TaskRunner(ctx, listener::onLog);
            if (riskPolicy != null) runner.setRiskPolicy(riskPolicy);
            runner.setApprover(approver);
            runner.setRemote(remote);
            StringBuilder doneActions = new StringBuilder();
            for (int i = 0; i < actions.length(); i++) {
                if (listener != null && listener.isStopped()) {
                    log("已手动停止");
                    return false;
                }
                JSONObject a;
                try {
                    a = actions.getJSONObject(i);
                } catch (Exception e) {
                    continue;
                }
                String do_ = a.optString("do", "");
                boolean adv = ALLOWED_ADV.contains(do_);
                if (!ALLOWED.contains(do_) && !(adv && allowAdvanced)) {
                    log("⛔ 跳过不允许的动作：" + do_);
                    continue;
                }
                // v2.45 试错：同一子任务内试过且失败的方法直接拦截，不执行
                String sig = actionSig(a);
                if (taskPlan.triedFailed(sig)) {
                    log("⛔ 该方法本子任务已试过且失败，拦截换招：" + sig);
                    pendingNudge = "你刚才想用的方法（" + sig + "）本子任务已经试过且失败，"
                            + "被系统拦截了。必须换一种没试过的方法，比如："
                            + "文本点不到→vision 看坐标→tap 坐标；"
                            + "打不开应用→确认包名/从桌面找图标；"
                            + "输入失败→先点输入框聚焦再输。";
                    break; // 停止本轮剩余动作，下轮重新思考
                }
                log("🖐 执行：" + do_ + (adv ? "（高级）" : ""));
                boolean ok = runner.execOneStep(a.toString());
                taskPlan.recordTried(sig, ok);
                if ("launch".equals(do_) && ok) {
                    String pkg = a.optString("package", "");
                    if (!pkg.isEmpty()) lastLaunchPkg = pkg;
                }
                doneActions.append(do_).append(ok ? "✓" : "✗");
                // 3. 重复动作检测
                recentActs.addLast(do_ + ":" + a.optString("text",
                        a.optString("cmd", "")));
                while (recentActs.size() > 3) recentActs.removeFirst();
                if (recentActs.size() == 3) {
                    String[] arr = recentActs.toArray(new String[0]);
                    if (arr[0].equals(arr[1]) && arr[1].equals(arr[2])) {
                        String repWarn = "【监管者实时提醒】连续 3 次重复同一动作（"
                                + arr[0] + "），别再重复，换方法";
                        log(repWarn);
                        history.append("\n").append(repWarn).append("\n");
                        recentActs.clear();
                    }
                }
                if (ok) lastProgress = System.currentTimeMillis();
                String out = runner.getLastOutput();
                if (out != null && !out.isEmpty()) {
                    doneActions.append("[输出:").append(out).append("]");
                }
                // 审计：记录每步动作 + 参数摘要 + 结果
                try {
                    JSONObject au = new JSONObject();
                    au.put("n", step);
                    au.put("do", do_);
                    au.put("ok", ok);
                    au.put("risk", ActionRisk.levelFor(do_).label);
                    if (out != null && !out.isEmpty()) {
                        au.put("out", out.length() > 120
                                ? out.substring(0, 120) + "…" : out);
                    }
                    auditSteps.add(au);
                } catch (Exception ignored) {
                }
                doneActions.append(" ");
                // v2.45：子任务尝试上限，超了标记失败跳过
                TaskPlan.Subtask cur = taskPlan.currentTask();
                if (cur != null && cur.attempts >= TaskPlan.MAX_ATTEMPTS
                        && !taskPlan.allDone()) {
                    log("❌ 子任务“" + cur.goal + "”已尝试 " + cur.attempts
                            + " 次未果，标记失败并跳过");
                    taskPlan.markFailed();
                    pendingNudge = "上一个子任务多次尝试失败已跳过，继续下一个子任务。";
                    break;
                }
                if (!ok) break; // 动作失败就重新看屏再想，不硬走
            }
            // v2.45：子任务完成判定（模型标记 subtask_done / 验证文本出现）
            TaskPlan.Subtask curT = taskPlan.currentTask();
            if (curT != null && !taskPlan.allDone()) {
                boolean modelDone = decision.optBoolean("subtask_done", false);
                boolean autoDone = !curT.verify.isEmpty()
                        && screen.contains(curT.verify);
                if (modelDone || autoDone) {
                    log("✅ 子任务完成：" + curT.goal
                            + (autoDone && !modelDone ? "（验证文本已出现）" : ""));
                    taskPlan.checkpoint(step, screenFingerprint(screen));
                    taskPlan.markDone();
                    if (taskPlan.allDone()) {
                        lastHistory = history.toString();
                        if (taskPlan.anyFailed()) {
                            log("⚠ 子任务已全部过完，但有失败项，任务未完全达成");
                            return false;
                        }
                        log("✅ 所有子任务完成，任务达成");
                        return true;
                    }
                }
            }
            rounds++;
            history.append("第").append(step).append("步：想[").append(think)
                    .append("] 做[").append(doneActions.toString().trim()).append("]\n");
            history = trimHistory(history);
        }
        lastHistory = history.toString();
        log("达到最大步数（" + maxSteps + "），停止");
        return false;
    }

    /** v2.45：构建结构化任务计划（thinker规划 > 旧todo > 规划调用 > 单任务兜底） */
    private TaskPlan buildTaskPlan(String goal) {
        TaskPlan p;
        if (!thinkerPlan.isEmpty()) {
            p = TaskPlan.fromThinkerText(thinkerPlan);
        } else if (!todoSteps.isEmpty()) {
            p = TaskPlan.fromGoals(todoSteps);
        } else {
            p = planByLlm(goal);
            if (p.empty()) p = TaskPlan.single(goal);
        }
        if (p.empty()) p = TaskPlan.single(goal);
        p.start();
        return p;
    }

    /** v2.45：一次规划调用，把目标拆成带验证标准的子任务 */
    private TaskPlan planByLlm(String goal) {
        try {
            log("📋 规划拆解中（1 次调用）…");
            String pp = "你是任务规划师。把下面的手机操作任务拆成 2-6 个子任务，"
                    + "每个子任务一句话目标 goal，外加完成后屏幕上应出现的验证文字 verify"
                    + "（无明确文字可空字符串）。只输出纯 JSON，不要 markdown 包裹，格式："
                    + "{\"subtasks\":[{\"goal\":\"打开系统设置\",\"verify\":\"设置\"}]}"
                    + "\n\n任务：" + goal;
            LlmClient.Result r = llm.chat(pp);
            if (!r.ok) {
                log("规划调用失败，走单任务模式");
                return new TaskPlan();
            }
            JSONObject ej = extractJson(r.text);
            if (ej == null) return new TaskPlan();
            return TaskPlan.fromJson(ej.toString());
        } catch (Exception e) {
            log("规划异常，走单任务模式");
            return new TaskPlan();
        }
    }

    /** v2.45：动作签名（用于试错去重）：do:关键参数 */
    private static String actionSig(JSONObject a) {
        String do_ = a.optString("do", "");
        if ("swipe".equals(do_)) {
            // 滑动的身份是 起点->终点
            return do_ + ":" + noSpace(a.optJSONArray("from"))
                    + "->" + noSpace(a.optJSONArray("to"));
        }
        String key = a.optString("text",
                a.optString("cmd",
                        a.optString("question",
                                a.optString("package",
                                        a.optString("path", "")))));
        Object id = a.opt("id");
        if (key.isEmpty() && id != null) key = String.valueOf(id);
        return do_ + ":" + key;
    }

    private static String noSpace(JSONArray arr) {
        return arr == null ? "" : arr.toString().replace(" ", "");
    }

    /**
     * v2.45 自恢复阶梯：L1 返回上一步 → L2 重新进入应用 → L3 回退到检查点。
     * 返回 false 表示已达恢复上限，调用方应停止任务。
     */
    private boolean doRecoveryLadder() {
        recoveryRounds++;
        if (recoveryRounds > MAX_RECOVERY_ROUNDS) {
            log("🛟 自恢复轮数超限（" + MAX_RECOVERY_ROUNDS + "），放弃任务");
            return false;
        }
        recoveryLevel++;
        TaskRunner runner = new TaskRunner(ctx, listener::onLog);
        if (riskPolicy != null) runner.setRiskPolicy(riskPolicy);
        runner.setApprover(approver);
        runner.setRemote(remote);
        try {
            if (recoveryLevel == 1) {
                log("🛟 自恢复 L1：返回上一步");
                runner.execOneStep("{\"do\":\"back\"}");
            } else if (recoveryLevel == 2 && !lastLaunchPkg.isEmpty()) {
                log("🛟 自恢复 L2：重新进入 " + lastLaunchPkg);
                runner.execOneStep("{\"do\":\"launch\",\"package\":\""
                        + lastLaunchPkg.replace("\"", "") + "\"}");
            } else {
                if (taskPlan.rollbackToCheckpoint()) {
                    log("🛟 自恢复 L3：回退到检查点（子任务 "
                            + (taskPlan.current() + 1) + "）重新尝试");
                } else {
                    log("🛟 自恢复 L3：无检查点可回退");
                }
                recoveryLevel = 0;
            }
        } catch (Exception e) {
            log("🛟 自恢复执行异常：" + e.getMessage());
        }
        return true;
    }

    /** 构建超时原因说明（给用户看的） */
    private String buildTimeoutReason(String goal, int step, long elapsed,
                                      long idle, String history) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务「").append(goal.length() > 20
                ? goal.substring(0, 20) + "…" : goal).append("」");
        if (elapsed > 5 * 60 * 1000) {
            sb.append("已运行 ").append(elapsed / 60000).append(" 分钟");
        } else {
            sb.append("已 ").append(idle / 1000).append(" 秒没有进展");
        }
        sb.append("（当前第 ").append(step).append(" 步）。\n");
        // 摘最近两步
        String[] lines = history.split("\n");
        int n = 0;
        for (int i = lines.length - 1; i >= 0 && n < 2; i--) {
            if (!lines[i].trim().isEmpty()) {
                sb.append("· ").append(lines[i].trim()).append("\n");
                n++;
            }
        }
        return sb.toString();
    }

    /** 界面序列化：紧凑文本，一行一个节点，省 token */
    static String serialize(List<AgentAccessibilityService.NodeInfo> nodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            AgentAccessibilityService.NodeInfo n = nodes.get(i);
            String t = n.text.isEmpty() ? n.desc : n.text;
            t = t.replace('\n', ' ').trim();
            if (t.isEmpty()) continue;
            if (t.length() > 24) t = t.substring(0, 24);
            sb.append('[').append(i).append("] ").append(t);
            if (n.clickable) sb.append(" (可点)");
            sb.append('\n');
        }
        return sb.toString();
    }

    private String skillCardsSection(String goal) {
        try {
            // 按需注入：只给与目标相关的卡片，省 token
            String d = new SkillCardStore(ctx).describeForPrompt(goal);
            if (d.isEmpty()) return "";
            return "可用技能卡（拿来即用，免重复思考；标注“优先”的用户技能卡优先调用）：\n"
                    + d + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    /** prompt 组装（纯逻辑，可独立测试） */
    String buildPrompt(String goal, String screen, String history, int step,
                         String cards) {
        StringBuilder p = new StringBuilder();
        p.append("你是手机自动化助手。你看到的是安卓手机当前界面的控件列表，")
                .append("格式为 [编号] 文字 (可点=可点击)。\n\n")
                .append("用户目标：").append(goal).append("\n\n");
        if (cards != null && !cards.isEmpty()) {
            p.append(cards).append("\n");
        }
        if (!conversationContext.isEmpty()) {
            p.append("【对话上下文】本次会话最近的对话，供参考：\n")
                    .append(conversationContext).append("\n\n");
        }
        if (!thinkerPlan.isEmpty()) {
            p.append("【思考者规划】请按以下计划执行，"
                    + "如界面与预期不符可灵活调整：\n")
                    .append(thinkerPlan).append("\n\n");
        }
        // v2.45：结构化任务计划（旧 todo 列表仅作兜底）
        String planDesc = taskPlan.describe();
        if (!planDesc.isEmpty()) {
            p.append(planDesc);
        } else {
            String todo = todoProgress();
            if (!todo.isEmpty()) p.append(todo).append("\n");
        }
        if (!supervisorFeedback.isEmpty()) {
            p.append("【监管者反馈】上次执行未被确认完成，继续执行：\n")
                    .append(supervisorFeedback)
                    .append("\n不要重复已完成的步骤，针对未完成的部分继续。\n\n");
        }
        if (!history.isEmpty()) {
            p.append("已做过的步骤：\n").append(history).append("\n");
        }
        if (!pendingNudge.isEmpty()) {
            p.append("【系统提醒】").append(pendingNudge).append("\n\n");
            pendingNudge = "";
        }
        p.append("当前界面（第 ").append(step).append(" 步）：\n")
                .append(screen).append("\n")
                .append("你每次回复必须是纯 JSON（不要 markdown 包裹），格式：\n")
                .append("{\"think\":\"简短思考\",\"done\":false,")
                .append("\"actions\":[{\"do\":\"tap_text\",\"text\":\"xxx\"}]}\n\n")
                .append("可用动作：\n")
                .append("- tap_text 按文本点 {\"do\":\"tap_text\",\"text\":\"设置\"}\n")
                .append("- tap_id 按编号点 {\"do\":\"tap_id\",\"id\":3}\n")
                .append("- vision 看屏问答（图标定位/判断画面）"
                        + " {\"do\":\"vision\",\"question\":\"快门按钮的坐标是多少？\"}"
                        + "（问位置会返回屏幕坐标，再用 tap 点）\n")
                .append("- tap 按坐标点击 {\"do\":\"tap\",\"x\":540,\"y\":2080}\n")
                .append("- input 输入文本（先点输入框） {\"do\":\"input\",\"text\":\"你好\"}\n")
                .append("- swipe 滑动 {\"do\":\"swipe\",\"from\":[500,1500],\"to\":[500,500]}\n")
                .append("- back 返回 {\"do\":\"back\"}\n")
                .append("- wait_text 等文本出现 {\"do\":\"wait_text\",\"text\":\"xxx\",\"timeout\":10}\n")
                .append("- launch 启动应用 {\"do\":\"launch\",\"package\":\"包名\"}\n")
                .append("- sleep 等待 {\"do\":\"sleep\",\"s\":2}\n")
                .append("- use_card 调用技能卡 {\"do\":\"use_card\",\"id\":\"card_xxx\"}"
                        + "（能用技能卡就别从零做）\n")
                .append("- termux 在内置 Termux 里跑命令"
                        + " {\"do\":\"termux\",\"cmd\":\"python3 /sdcard/a.py\"}"
                        + "（⭐优先：能用命令行解决的就别点 UI，"
                        + "比如查信息、算数、处理文件、跑脚本）\n")
                .append("- search 联网搜索"
                        + " {\"do\":\"search\",\"query\":\"北京天气\",\"count\":5}"
                        + "（需要外部信息时先搜，搜完再用结果继续任务）\n")
                .append("- shell 执行命令（root） {\"do\":\"shell\",\"cmd\":\"ls /sdcard\"}\n")
                .append("- read_file 读文件 {\"do\":\"read_file\",\"path\":\"/sdcard/a.txt\"}\n")
                .append("- write_file 写文件 {\"do\":\"write_file\",\"path\":\"/sdcard/a.txt\",\"content\":\"hi\"}\n")
                .append("- fetch_url 抓网页正文 {\"do\":\"fetch_url\",\"url\":\"https://...\"}\n\n")
                .append("工作流（必须遵守）：\n")
                .append("A. 理解：先看清目标是什么，不要急着动手\n")
                .append("B. 拆解：把目标拆成 2-5 个小步骤，心里有 todo 列表\n")
                .append("C. 执行：每次只做 1 个小动作\n")
                .append("D. 验证：动作后看屏幕是否按预期变化，没变化=失败\n")
                .append("E. 排错：失败就换方法（文本点不到→vision 看坐标→tap 坐标），"
                        + "不要重复同一个失败动作；"
                        + "同一子任务内试过且失败的方法会被系统直接拦截不再执行，"
                        + "【硬性禁令】被拦截的方法换写法再用也一样被拦，别浪费步数\n")
                .append("F. 总结：全部做完后说做了什么、结果如何\n\n")
                .append("👆 点击智能（核心能力）：\n")
                .append("- 看到目标先想：这是文字按钮还是图标？\n")
                .append("- 文字按钮 → 直接 tap_text 点文字\n")
                .append("- 图标按钮（相机快门、返回箭头等）→ 用 vision 问位置拿坐标 → tap 坐标\n")
                .append("- 点之前确认目标在当前屏幕上，别点不存在的东西\n")
                .append("- 点之后看屏幕变化：没变化=没点中，换方法（vision 重新定位）\n")
                .append("- tap_text 报告\"超时未找到\"时，禁止凭感觉猜坐标点；"
                        + "必须先用 vision 问出坐标再 tap\n")
                .append("- 不要连续点同一个位置 3 次以上\n\n")
                .append("规则：\n")
                .append("0a. ⭐ 先复述再动手：首轮 think 必须用一句话复述用户目标"
                        + "+\"做成什么样算成功\"，然后再给动作\n")
                .append("0b. ⭐ 眼睛不够用时看屏幕：如果文本列表里找不到目标"
                        + "（比如相机快门是图标、要判断前后摄像头），"
                        + "就用 vision 提问，别反复乱点；"
                        + "vision 返回坐标后，用 tap 按坐标点\n")
                .append("0. ⭐ Termux 优先：凡是命令行能搞定的（查资料、计算、文件处理、"
                        + "联网取数），一律先用 termux，别急着点屏幕；"
                        + "需要外部知识时先用 search 搜，拿到结果再行动\n")
                .append("1. 每次只做 1-3 个小动作，不要贪多\n")
                .append("2. 动作失败就换思路，不要重复点同一个\n")
                .append("3. 目标达成时返回 {\"think\":\"...\",\"done\":true,\"actions\":[]}\n")
                .append("3b. 当前子任务的验证标准达成时，在 JSON 里加 "
                        + "\"subtask_done\":true（任务计划里标了验证标准的看计划）\n")
                .append("4. 只输出 JSON，不要解释\n")
                .append("5. 注意：shell/termux/联网/写文件是高风险动作，"
                        + "执行前可能要用户确认；能用低风险动作（点击/读屏）"
                        + "搞定的别绕路\n");
        return p.toString();
    }

    /** 只保留最近 HISTORY_KEEP 轮历史：最近 2 轮完整，更早的只留动作摘要（省 token） */
    static StringBuilder trimHistory(StringBuilder h) {
        String[] lines = h.toString().split("\n");
        if (lines.length <= HISTORY_KEEP) return h;
        StringBuilder out = new StringBuilder();
        int start = lines.length - HISTORY_KEEP;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (i < lines.length - 2) line = compressHistoryLine(line);
            out.append(line).append('\n');
        }
        return out;
    }

    /**
     * 老历史行压缩：保留「做[动作摘要]」完整，「想[思考]」截断到 40 字。
     * 格式：第N步：想[...] 做[...]
     */
    static String compressHistoryLine(String line) {
        int zi = line.indexOf("想[");
        int zo = line.indexOf("] 做[", zi);
        if (zi < 0 || zo < 0) return line;
        String think = line.substring(zi + 2, zo);
        if (think.length() > 40) think = think.substring(0, 40) + "…";
        return line.substring(0, zi + 2) + think + line.substring(zo);
    }

    /** 从模型回复中提取 JSON（容忍 markdown 包裹） */
    static JSONObject extractJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 去 markdown 代码块
        if (t.startsWith("```")) {
            int s = t.indexOf('\n');
            int e = t.lastIndexOf("```");
            if (s > 0 && e > s) t = t.substring(s + 1, e).trim();
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(t.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
