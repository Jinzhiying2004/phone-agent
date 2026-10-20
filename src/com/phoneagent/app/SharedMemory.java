package com.phoneagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
* v3.44 一份共享记忆（用户定调：一个智能体、三种人格）。
* App / Telegram / 远控 共用同一份：
* 1) 对话轮次（shared_rounds.json）——任何通道完成一轮任务都写进来，
* 其他通道下一次任务能看到上下文，不再各记各的、重启就忘；
* 2) 任务经验（experience.json）——每次任务的结局（目标/成败/耗时/工具数）
* 自动沉淀，新任务开始前按目标相似度注入相关先例，
* 哪个人格踩过的坑，另外两个人格不再重复踩。
*/
public class SharedMemory {

private static final int MAX_ROUNDS = 30;
private static final int MAX_EXP = 200;

// ---------------- 对话轮次 ----------------

public static synchronized void addRound(Context ctx, String ch,
String goal, String result) {
try {
JSONObject root = read(ctx, "shared_rounds.json");
JSONArray arr = root.optJSONArray("rounds");
if (arr == null) arr = new JSONArray();
JSONObject r = new JSONObject();
r.put("ts", System.currentTimeMillis());
r.put("ch", ch);
r.put("goal", trim(goal, 80));
r.put("result", trim(result, 120));
arr.put(r);
while (arr.length() > MAX_ROUNDS) arr.remove(0);
root.put("rounds", arr);
write(ctx, "shared_rounds.json", root);
} catch (Exception ignored) {
}
}

/** v3.45：三端共用的结果尾巴——答案/明细截 60 字，超长加省略号。 */
public static String tail60(String s) {
if (s == null || s.isEmpty()) return "";
return s.length() > 60 ? s.substring(0, 60) + "…" : s;
}

/** 最近 N 轮拼成上下文文本；没有返回空串。 */
public static synchronized String recentText(Context ctx, int maxRounds) {
try {
JSONObject root = read(ctx, "shared_rounds.json");
JSONArray arr = root.optJSONArray("rounds");
if (arr == null || arr.length() == 0) return "";
StringBuilder sb = new StringBuilder();
sb.append("\n");
int from = Math.max(0, arr.length() - maxRounds);
for (int i = from; i < arr.length(); i++) {
JSONObject r = arr.getJSONObject(i);
sb.append("用户：").append(r.optString("goal")).append("\n");
sb.append("助手：").append(r.optString("result")).append("\n");
}
return sb.toString().trim();
} catch (Exception e) {
return "";
}
}

/** /new：三端任一通道新开对话，共享轮次一并清。 */
public static synchronized void clear(Context ctx) {
try {
JSONObject root = new JSONObject();
root.put("rounds", new JSONArray());
write(ctx, "shared_rounds.json", root);
} catch (Exception ignored) {
}
}

// ---------------- 任务经验 ----------------

public static synchronized void addExperience(Context ctx, String ch,
String goal, boolean ok,
long ms, int tools,
String note) {
try {
JSONObject root = read(ctx, "experience.json");
JSONArray arr = root.optJSONArray("items");
if (arr == null) arr = new JSONArray();
long now = System.currentTimeMillis();
String g = trim(goal, 80);
// 10 分钟内同目标同结局的重试只更新最新一条，不刷屏
if (arr.length() > 0) {
JSONObject last = arr.getJSONObject(arr.length() - 1);
if (g.equals(last.optString("goal"))
&& ok == last.optBoolean("ok")
&& now - last.optLong("ts") < 10 * 60_000L) {
last.put("ts", now);
last.put("ms", ms);
last.put("tools", tools);
root.put("items", arr);
write(ctx, "experience.json", root);
return;
}
}
JSONObject it = new JSONObject();
it.put("ts", now);
it.put("ch", ch);
it.put("goal", g);
it.put("ok", ok);
it.put("ms", ms);
it.put("tools", tools);
it.put("note", trim(note, 60));
arr.put(it);
while (arr.length() > MAX_EXP) arr.remove(0);
root.put("items", arr);
write(ctx, "experience.json", root);
} catch (Exception ignored) {
}
}

/** 按目标相似度找最相关的历史结局（最多 2 条），拼成注入文本；无返回空串。 */
public static synchronized String relevantExperience(Context ctx,
String goal) {
try {
JSONObject root = read(ctx, "experience.json");
JSONArray arr = root.optJSONArray("items");
if (arr == null || arr.length() == 0 || goal == null) return "";
Set<String> mine = bigrams(goal);
if (mine.isEmpty()) return "";
List<double[]> scored = new ArrayList<>();
for (int i = 0; i < arr.length(); i++) {
JSONObject it = arr.getJSONObject(i);
Set<String> theirs = bigrams(it.optString("goal"));
if (theirs.isEmpty()) continue;
int inter = 0;
for (String g: mine) if (theirs.contains(g)) inter++;
double j = (double) inter / (mine.size() + theirs.size() - inter);
if (j >= 0.15) scored.add(new double[]{j, i});
}
if (scored.isEmpty()) return "";
scored.sort((a, b) -> Double.compare(b[0], a[0]));
StringBuilder sb = new StringBuilder();
sb.append("\n");
boolean hasFail = false;
int n = Math.min(2, scored.size());
for (int k = 0; k < n; k++) {
JSONObject it = arr.getJSONObject((int) scored.get(k)[1]);
boolean ok = it.optBoolean("ok");
if (!ok) hasFail = true;
sb.append("- 「").append(it.optString("goal")).append("」")
.append(chName(it.optString("ch"))).append(" · ")
.append(ok? "已完成": "未完成")
.append("（用时 ").append(it.optLong("ms") / 1000)
.append(" 秒，").append(it.optInt("tools"))
.append(" 次工具调用）\n");
}
if (hasFail) {
sb.append("提示：失败的先例先核实前提是否存在再动手，"
+ "别重复无效探索；如果这次条件已变，以当前观察为准。");
}
return sb.toString().trim();
} catch (Exception e) {
return "";
}
}

// ---------------- 工具 ----------------

private static String chName(String ch) {
if ("tg".equals(ch)) return "Telegram";
if ("remote".equals(ch)) return "远控";
if ("app".equals(ch)) return "App";
return ch;
}

private static Set<String> bigrams(String s) {
Set<String> out = new HashSet<>();
if (s == null) return out;
String t = s.replaceAll("[\\s\\p{P}]", "");
if (t.length() < 2) {
if (!t.isEmpty()) out.add(t);
return out;
}
for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
return out;
}

private static String trim(String s, int max) {
if (s == null) return "";
s = s.trim();
return s.length() > max? s.substring(0, max) + "…": s;
}

private static JSONObject read(Context ctx, String name) {
try {
File f = new File(ctx.getFilesDir(), name);
if (!f.exists()) return new JSONObject();
FileInputStream in = new FileInputStream(f);
byte[] buf = new byte[(int) f.length()];
int off = 0, r;
while (off < buf.length
&& (r = in.read(buf, off, buf.length - off)) > 0) {
off += r;
}
in.close();
if (off == 0) return new JSONObject();
return new JSONObject(new String(buf, 0, off, "UTF-8"));
} catch (Exception e) {
return new JSONObject();
}
}

private static void write(Context ctx, String name, JSONObject root) {
try {
File f = new File(ctx.getFilesDir(), name);
FileOutputStream out = new FileOutputStream(f);
out.write(root.toString().getBytes("UTF-8"));
out.close();
} catch (Exception ignored) {
}
}
}
