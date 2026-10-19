package com.phoneagent.app;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 原生 Telegram Bot（Bot API 长轮询，无第三方依赖）。
 * <p>
 * 手机即 Bot：用户在 Telegram 里给机器人发指令，手机执行后回消息。
 * 不依赖 Tailscale，纯公网通道。安全：只有绑定的主人 chatId 能指挥，
 * 首次 /start 自动绑定，陌生人消息直接忽略。
 * <p>
 * 指令：
 * /start 绑定主人 ｜ /help 帮助 ｜ /status 状态
 * /task {json} 执行任务 ｜ /agent 目标 AI 自动操控 ｜ /shot 截屏
 * 直接发文字 = /agent 目标
 */
public class TgBot {

    public interface Listener {
        void onLog(String s);
    }

    private static final String API = "https://api.telegram.org/bot";
    private static final int MSG_LIMIT = 4000;

    private final Context ctx;
    private final TgStore store;
    private final LlmStore llmStore;
    private final Listener listener;
    private volatile boolean running;
    // v2.98：Telegram 任务级停止标志
    private volatile boolean tgTaskStop = false;
    private volatile boolean tgTaskRunning = false;
    // v2.98：流式推送节流
    private long tgLastPushMs = 0;
    private Thread thread;
    private long offset = 0;

    public TgBot(Context ctx, TgStore store, LlmStore llmStore, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        this.llmStore = llmStore;
        this.listener = listener;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    public synchronized void start() {
        if (running) return;
        if (!store.hasToken()) {
            log("未配置 Bot Token");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "tgbot");
        thread.start();
        log("Telegram Bot 已启动，等待指令…");
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        log("Telegram Bot 已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 长轮询主循环（跑在 bot 线程） */
    private void loop() {
        int failStreak = 0;
        while (running) {
            try {
                JSONObject resp = apiGet("getUpdates?offset=" + offset + "&timeout=30");
                if (resp == null || !resp.optBoolean("ok")) {
                    failStreak++;
                    // v3.03：连续失败才打日志，偶发网络抖动不刷屏
                    if (failStreak >= 3) log("轮询异常（连续" + failStreak + "次）");
                    sleepQuiet(3000);
                    continue;
                }
                failStreak = 0;
                JSONArray result = resp.optJSONArray("result");
                if (result == null) continue;
                for (int i = 0; i < result.length(); i++) {
                    if (!running) break;
                    JSONObject u = result.optJSONObject(i);
                    if (u == null) continue;
                    offset = Math.max(offset, u.optLong("update_id", 0) + 1);
                    handleUpdate(u);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) break;
                if (!running) break;
                failStreak++;
                if (failStreak >= 3) log("轮询异常：" + e.getMessage() + "（连续" + failStreak + "次）");
                sleepQuiet(5000);
            } catch (Exception e) {
                if (!running) break;
                failStreak++;
                if (failStreak >= 3) log("轮询异常：" + e.getMessage() + "（连续" + failStreak + "次）");
                sleepQuiet(5000);
            }
        }
        running = false;
    }

    private void handleUpdate(JSONObject u) {
        JSONObject msg = u.optJSONObject("message");
        if (msg == null) return;
        long chatId = msg.optJSONObject("chat") != null
                ? msg.optJSONObject("chat").optLong("id", 0) : 0;
        String text = msg.optString("text", "").trim();
        if (chatId == 0 || text.isEmpty()) return;

        // 主人绑定
        if (!store.hasOwner()) {
            if (text.equals("/start")) {
                store.bindOwner(chatId);
                log("已绑定主人 chatId=" + chatId);
                sendMessage(chatId, "绑定成功 ✅\n\n"
                        + "直接发文字我就照做（如\"打开微信\")。\n"
                        + "指令：/status 状态 /task {json} 任务 /shot 截屏 /help 帮助");
            }
            return; // 未绑定时其他消息一律忽略
        }
        if (chatId != store.getOwner()) return; // 陌生人忽略

        log("📩 " + brief(text));
        try {
            if (text.equals("/help") || text.equals("/start")) {
                sendMessage(chatId, helpText());
            } else if (text.equals("/status")) {
                sendMessage(chatId, statusText());
            } else if (text.equals("/shot")) {
                sendMessage(chatId, "截屏中…");
                sendScreenshot(chatId);
            } else if (text.equals("/stop")) {
                // v2.98：强行打断正在跑的任务
                if (!tgTaskRunning) {
                    sendMessage(chatId, "没有正在执行的任务");
                } else if (tgTaskStop) {
                    sendMessage(chatId, "已在停止中…");
                } else {
                    tgTaskStop = true;
                    sendMessage(chatId, "🛑 已请求停止，正在打断…");
                }
            } else if (text.startsWith("/task")) {
                runTaskCmd(chatId, text.substring(5).trim());
            } else if (text.startsWith("/agent")) {
                runAgentCmd(chatId, text.substring(6).trim());
            } else {
                runAgentCmd(chatId, text); // 直接发文字 = 智能执行
            }
        } catch (Exception e) {
            sendMessage(chatId, "执行异常：" + e.getMessage());
        }
    }

    private String helpText() {
        return "📱 手机 Agent 指令\n\n"
                + "/status —— 服务状态\n"
                + "/shot —— 当前截屏\n"
                + "/stop —— 强行打断当前任务\n"
                + "/task {\"steps\":[...]} —— 执行任务 JSON\n"
                + "/agent 打开微信 —— AI 看屏自动操控\n"
                + "直接发文字 = /agent\n\n"
                + "解绑：在 App 里点「解绑主人」";
    }

    private String statusText() {
        boolean svc = AgentService.isRunning();
        boolean acc = AgentAccessibilityService.isRunning();
        return "📊 状态\n"
                + "无障碍：" + (acc ? "运行中 ✅" : "未开启 ❌") + "\n"
                + "远控服务：" + (svc ? "运行中 ✅" : "未启动 ❌") + "\n"
                + "大模型：" + (llmStore.hasKey() ? "已配置 ✅" : "未配置 ❌") + "\n"
                + "Telegram：运行中 ✅";
    }

    /** /task：执行任务 JSON */
    private void runTaskCmd(long chatId, String json) {
        if (json.isEmpty()) {
            sendMessage(chatId, "用法：/task {\"steps\":[...]}");
            return;
        }
        sendMessage(chatId, "▶ 开始执行任务…");
        StringBuilder logs = new StringBuilder();
        TaskRunner runner = new TaskRunner(ctx, s -> {
            logs.append(s).append("\n");
            log("[task] " + s);
        });
        // 远程任务：ASK 策略的高风险动作无审批人时拦截
        runner.setRemote(true);
        boolean ok;
        try {
            ok = runner.run(json);
        } catch (Exception e) {
            sendMessage(chatId, "任务解析失败：" + e.getMessage());
            return;
        }
        sendMessage(chatId, (ok ? "✅ 任务完成\n\n" : "⏹ 任务结束（未完成）\n\n")
                + trimLog(logs.toString()));
    }

    /** /agent：AI 看屏自动操控 */
    private void runAgentCmd(long chatId, String goal) {
        if (goal.isEmpty()) {
            sendMessage(chatId, "用法：/agent 打开时钟设个 7 点的闹钟");
            return;
        }
        // v3.00：闲聊分流（和 App 内一致）
        // v3.04：TG 流式恢复（LlmClient null bug 已修）
        // v3.06：加 typing 指示器
        if (LocalIntentRouter.isChat(goal)) {
            sendTyping(chatId);
            String prompt = "你是手机Agent，一个友好、简洁的手机助手。用户说：" + goal
                    + "\n用一句话友好回应，如果用户没说具体要做什么，问他想让我干什么。不要输出JSON。";
            final long msgId = sendMessageReturnId(chatId, "🤖 …");
            final StringBuilder full = new StringBuilder("🤖 ");
            final long[] lastEdit = {0};
            final long[] lastTyping = {0};
            LlmClient.Result r = llmStore.client().chatStream(prompt, token -> {
                full.append(token);
                long now = System.currentTimeMillis();
                // typing 指示器每 4 秒刷一次（TG 5 秒过期）
                if (now - lastTyping[0] > 4000) {
                    lastTyping[0] = now;
                    sendTyping(chatId);
                }
                // 1.5 秒节流编辑，防 TG 限流
                if (now - lastEdit[0] > 1500) {
                    lastEdit[0] = now;
                    editMessage(chatId, msgId, full.toString());
                }
            });
            // 最终确保完整
            if (r.ok) {
                editMessage(chatId, msgId, full.toString());
            } else {
                editMessage(chatId, msgId, "出错了：" + r.text);
            }
            return;
        }
        // 高频意图先走本地路由（零 token）
        try {
            LocalIntentRouter.RouteResult rr =
                    LocalIntentRouter.tryRoute(ctx, goal);
            if (rr != null) {
                sendMessage(chatId, (rr.handled ? "✅ " : "⚠️ ") + rr.message);
                if (rr.handled) return;
            }
        } catch (Exception ignored) {
        }
        if (!llmStore.hasKey()) {
            sendMessage(chatId, "先在 App「大模型配置」里填好 API Key");
            return;
        }
        if (!AgentAccessibilityService.isRunning()) {
            sendMessage(chatId, "无障碍权限未开启，AI 看不见屏幕");
            return;
        }
        sendMessage(chatId, "🎯 收到：" + goal + "\n开始执行（最多 12 步）…");
        // v3.06：任务执行时也显示 typing
        sendTyping(chatId);
        // v2.98：重置停止标志，标记任务运行中
        tgTaskStop = false;
        tgTaskRunning = true;
        StringBuilder logs = new StringBuilder();
        AgentLoop loop = new AgentLoop(ctx, llmStore.client(),
                new AgentLoop.Listener() {
                    @Override
                    public void onLog(String msg) {
                        logs.append(msg).append("\n");
                        log("[agent] " + msg);
                        // v2.98：实时流式推送（节流 2 秒，只推关键信息）
                        if (shouldStream(msg)) {
                            long now = System.currentTimeMillis();
                            if (now - tgLastPushMs > 2000) {
                                tgLastPushMs = now;
                                sendMessage(chatId, msg);
                            }
                        }
                    }

                    @Override
                    public boolean isStopped() {
                        // v2.98：支持 /stop 强行打断
                        return !running || tgTaskStop;
                    }
                });
        // 远程（Telegram）进来：高风险动作无审批人时默认拦截，不自动放行
        loop.setRemote(true);
        loop.setApprover((action, detail) -> {
            sendMessage(chatId, "⛔ 远程拦截高风险动作：" + action
                    + "\n在手机 App 上执行可弹窗确认。");
            return false;
        });
        boolean done;
        try {
            done = loop.run(goal, 12);
        } catch (Exception e) {
            sendMessage(chatId, "执行异常：" + e.getMessage());
            tgTaskRunning = false;
            return;
        } finally {
            tgTaskRunning = false;
        }
        String stopNote = tgTaskStop ? "（被 /stop 打断）\n\n" : "\n\n";
        sendMessage(chatId, (done ? "🎉 任务完成" : "⏹ 任务结束（未完成）")
                + stopNote + trimLog(logs.toString()));
    }

    // v2.98：判断哪些消息值得实时推送（关键节点，不刷屏）
    private boolean shouldStream(String msg) {
        return msg.startsWith("💭") || msg.startsWith("📍")
                || msg.startsWith("🔍") || msg.startsWith("🤖")
                || msg.startsWith("✅") || msg.startsWith("❌");
    }

    /** /shot：截屏发照片 */
    private void sendScreenshot(long chatId) {
        try {
            Bitmap bmp = AgentAccessibilityService.screenshot();
            if (bmp == null) {
                sendMessage(chatId, "截屏失败（无障碍未开启或系统拒绝）");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // 压缩后再发：省流量（本地看仍是原图）
            byte[] small = ScreenshotUtil.toJpegSmall(bmp, 720, 70);
            byte[] data = small != null ? small : bos.toByteArray();
            if (small == null) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                data = bos.toByteArray();
            }
            boolean ok = sendPhoto(chatId, data, "📸 当前屏幕");
            if (!ok) sendMessage(chatId, "照片发送失败");
        } catch (Exception e) {
            sendMessage(chatId, "截屏异常：" + e.getMessage());
        }
    }

    // ---------- Bot API 底层 ----------

    private JSONObject apiGet(String path) throws Exception {
        URL url = new URL(API + store.getToken() + "/" + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        int code = c.getResponseCode();
        String body = readAll(c, code);
        if (code != 200) {
            log("Telegram API 错误 HTTP " + code);
            return null;
        }
        return new JSONObject(body);
    }

    private void sendMessage(long chatId, String text) {
        sendMessageReturnId(chatId, text);
    }

    /** v3.02：发送并返回 message_id（用于流式编辑） */
    private long sendMessageReturnId(long chatId, String text) {
        if (text == null || text.isEmpty()) return 0;
        long msgId = 0;
        try {
            // 超长分段（只返回第一段的 id）
            for (int i = 0; i < text.length(); i += MSG_LIMIT) {
                String part = text.substring(i, Math.min(i + MSG_LIMIT, text.length()));
                String params = "chat_id=" + chatId
                        + "&text=" + URLEncoder.encode(part, "UTF-8");
                URL url = new URL(API + store.getToken() + "/sendMessage");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(params.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                String resp = readAll(c, code);
                if (code != 200) {
                    log("发送消息失败 HTTP " + code);
                } else if (msgId == 0) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(resp);
                        msgId = o.optJSONObject("result").optLong("message_id", 0);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log("发送消息异常：" + e.getMessage());
        }
        return msgId;
    }

    /** v3.06：发送 typing 指示器（原生流式体验） */
    private void sendTyping(long chatId) {
        try {
            String params = "chat_id=" + chatId + "&action=typing";
            URL url = new URL(API + store.getToken() + "/sendChatAction");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            }
            readAll(c, c.getResponseCode());
        } catch (Exception ignored) {
        }
    }

    /** v3.02：编辑消息（流式更新用） */
    private void editMessage(long chatId, long msgId, String text) {
        if (msgId == 0 || text == null) return;
        try {
            String params = "chat_id=" + chatId
                    + "&message_id=" + msgId
                    + "&text=" + URLEncoder.encode(
                            text.length() > MSG_LIMIT
                                    ? text.substring(0, MSG_LIMIT) : text,
                            "UTF-8");
            URL url = new URL(API + store.getToken() + "/editMessageText");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            readAll(c, code);
            // 429 或内容未变都静默忽略（节流已做）
        } catch (Exception e) {
            log("编辑消息异常：" + e.getMessage());
        }
    }

    private boolean sendPhoto(long chatId, byte[] png, String caption) {
        String boundary = "----PhoneAgent" + System.currentTimeMillis();
        try {
            URL url = new URL(API + store.getToken() + "/sendPhoto");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            try (OutputStream os = c.getOutputStream()) {
                writePart(os, boundary, "chat_id",
                        String.valueOf(chatId).getBytes(StandardCharsets.UTF_8), null);
                writePart(os, boundary, "caption",
                        caption.getBytes(StandardCharsets.UTF_8), null);
                writePart(os, boundary, "photo", png, "shot.png");
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            readAll(c, code);
            return code == 200;
        } catch (Exception e) {
            log("发送照片异常：" + e.getMessage());
            return false;
        }
    }

    private void writePart(OutputStream os, String boundary, String name,
                           byte[] data, String filename) throws Exception {
        StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) h.append("; filename=\"").append(filename).append("\"");
        h.append("\r\n");
        if (filename != null) h.append("Content-Type: image/png\r\n");
        h.append("\r\n");
        os.write(h.toString().getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String readAll(HttpURLConnection c, int code) throws Exception {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String trimLog(String s) {
        if (s.length() <= MSG_LIMIT) return s;
        return "…(日志过长，只显示末尾)\n" + s.substring(s.length() - MSG_LIMIT);
    }

    private static String brief(String s) {
        s = s.replace("\n", " ");
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
