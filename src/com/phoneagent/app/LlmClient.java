package com.phoneagent.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容接口的极简客户端（/v1/models + /v1/chat/completions）。
 * 纯 Java（java.net），无第三方依赖，可在桌面 JVM 实测。
 * 注意：apiKey 仅用于发往用户自己配置的 baseUrl，绝不打日志。
 */
public class LlmClient {

    public static class Result {
        public boolean ok;
        /** 成功时为模型回复/连接信息，失败时为错误描述 */
        public String text;
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public LlmClient(String baseUrl, String apiKey, String model) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    /** 测试连接：GET /v1/models */
    public Result testConnection() {
        Result r = new Result();
        try {
            HttpURLConnection c = open("/v1/models", "GET");
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                int n = countOccurrences(body, "\"id\"");
                r.ok = true;
                r.text = "连接成功，可用模型约 " + n + " 个";
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "连接失败：" + e.getMessage();
        }
        return r;
    }

    /** 一问一答：POST /v1/chat/completions */
    /**
     * v2.51 借鉴 ApkClaw：失败指数退避重试（1s→2s→4s），401/403 不重试。
     */
    public Result chat(String prompt) {
        return withRetry(() -> doChat(prompt));
    }

    /** 流式回调：收到 token 片段时调用 */
    public interface StreamCallback {
        void onToken(String token);
    }

    /**
     * v3.02：真流式输出（SSE）。
     * 每个 token 到达时调 onToken，全收完返回完整 Result。
     */
    public Result chatStream(String prompt, StreamCallback cb) {
        Result r = new Result();
        StringBuilder full = new StringBuilder();
        try {
            HttpURLConnection c = open("/v1/chat/completions", "POST");
            String payload = "{\"model\":\"" + esc(model)
                    + "\",\"stream\":true"
                    + ",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + esc(prompt) + "\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(readBody(c, code));
                return r;
            }
            // 逐行读 SSE
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(data);
                        org.json.JSONArray choices = o.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        org.json.JSONObject delta = choices.optJSONObject(0)
                                .optJSONObject("delta");
                        if (delta == null || delta.isNull("content")) continue;
                        // v3.04：org.json 的 optString 对 JSON null 会返回字符串"null"，必须过滤
                        String token = delta.optString("content", "");
                        if (token.isEmpty() || "null".equals(token)) continue;
                        full.append(token);
                        if (cb != null) {
                            try { cb.onToken(token); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {
                        // 单行解析失败跳过
                    }
                }
            }
            r.ok = true;
            r.text = full.toString();
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    private Result withRetry(java.util.function.Supplier<Result> call) {
        Result r = null;
        long[] waits = {1000, 2000, 4000};
        for (int i = 0; i <= waits.length; i++) {
            r = call.get();
            if (r.ok) return r;
            // key 错了重试没用，直接返回
            if (r.text != null && (r.text.contains("HTTP 401") || r.text.contains("HTTP 403"))) {
                return r;
            }
            if (i < waits.length) {
                try { Thread.sleep(waits[i]); } catch (InterruptedException ignored) {}
            }
        }
        return r;
    }

    private Result doChat(String prompt) {
        Result r = new Result();
        try {
            HttpURLConnection c = open("/v1/chat/completions", "POST");
            String payload = "{\"model\":\"" + esc(model)
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + esc(prompt) + "\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                String content = extractContent(body);
                if (content != null) {
                    r.ok = true;
                    r.text = content;
                } else {
                    r.ok = false;
                    r.text = "解析回复失败";
                }
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    /**
     * 视觉问答：把截图发给视觉模型。
     * image 传 Bitmap（会自动压缩到 720p JPEG），visionModel 为空则用主 model。
     */
    public Result chatVision(String prompt, android.graphics.Bitmap image,
                             String visionModel) {
        return withRetry(() -> doChatVision(prompt, image, visionModel));
    }

    private Result doChatVision(String prompt, android.graphics.Bitmap image,
                             String visionModel) {
        Result r = new Result();
        try {
            if (image == null) {
                r.ok = false;
                r.text = "截图为空";
                return r;
            }
            // 压缩到 720p 以内，省 token
            android.graphics.Bitmap small = scaleDown(image, 720);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
            if (small != image) small.recycle();
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                    android.util.Base64.NO_WRAP);
            String vm = visionModel == null || visionModel.isEmpty()
                    ? model : visionModel;
            HttpURLConnection c = open("/v1/chat/completions", "POST");
            // OpenAI vision 格式：content 为数组
            String payload = "{\"model\":\"" + esc(vm) + "\",\"messages\":"
                    + "[{\"role\":\"user\",\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"" + esc(prompt) + "\"},"
                    + "{\"type\":\"image_url\",\"image_url\":"
                    + "{\"url\":\"data:image/jpeg;base64," + b64 + "\"}}]}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                String content = extractContent(body);
                if (content != null) {
                    r.ok = true;
                    r.text = content;
                } else {
                    r.ok = false;
                    r.text = "解析回复失败";
                }
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    /** 等比缩放到 maxDim 以内 */
    private static android.graphics.Bitmap scaleDown(
            android.graphics.Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxDim) return src;
        float s = (float) maxDim / m;
        return android.graphics.Bitmap.createScaledBitmap(src,
                Math.round(w * s), Math.round(h * s), true);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private String readBody(HttpURLConnection c, int code) throws Exception {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 错误 body 截断，避免超长刷屏 */
    private String brief(String body) {
        if (body == null) return "";
        String t = body.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private int countOccurrences(String s, String sub) {
        int count = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            count++;
            i += sub.length();
        }
        return count;
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从 chat/completions 响应中提取第一条 message.content。
     * 先定位 "choices" 数组，再在其后找 "content"，避免误命中。
     */
    static String extractContent(String body) {
        int ci = body.indexOf("\"choices\"");
        if (ci < 0) return null;
        return extractJsonString(body, "content", ci);
    }

    /** 从 fromIndex 起找 key 对应的 JSON 字符串值（含转义还原） */
    static String extractJsonString(String json, String key, int fromIndex) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k, fromIndex);
        while (i >= 0) {
            int j = i + k.length();
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
            if (j < json.length() && json.charAt(j) == ':') {
                j++;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                if (j < json.length() && json.charAt(j) == '"') {
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < json.length()) {
                        char ch = json.charAt(j);
                        if (ch == '\\' && j + 1 < json.length()) {
                            char e2 = json.charAt(j + 1);
                            switch (e2) {
                                case 'n': sb.append('\n'); break;
                                case 't': sb.append('\t'); break;
                                case 'r': sb.append('\r'); break;
                                case '"': sb.append('"'); break;
                                case '\\': sb.append('\\'); break;
                                case '/': sb.append('/'); break;
                                case 'u':
                                    if (j + 5 < json.length()) {
                                        try {
                                            sb.append((char) Integer.parseInt(
                                                    json.substring(j + 2, j + 6), 16));
                                        } catch (NumberFormatException ignored) {
                                        }
                                        j += 4;
                                    }
                                    break;
                                default: sb.append(e2); break;
                            }
                            j += 2;
                        } else if (ch == '"') {
                            return sb.toString();
                        } else {
                            sb.append(ch);
                            j++;
                        }
                    }
                    return null; // 字符串未闭合
                }
            }
            i = json.indexOf(k, i + 1);
        }
        return null;
    }
}
