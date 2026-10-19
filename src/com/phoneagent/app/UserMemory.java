package com.phoneagent.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
* v2.85：用户长期记忆（偏好/事实）。
* 存 SharedPreferences，智能体 via remember 动作写入，prompt 注入时读出。
*/
public class UserMemory {

private static final String PREF = "user_memory";

/** 写入一条记忆 */
public static void put(Context ctx, String key, String value) {
if (key == null || key.isEmpty()) return;
ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
.edit().putString(key.trim(), value == null? "": value.trim())
.apply();
}

/** 删除一条记忆 */
public static void remove(Context ctx, String key) {
if (key == null) return;
ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
.edit().remove(key.trim()).apply();
}

/** 读出全部记忆，拼成 prompt 用文本 */
public static String describeForPrompt(Context ctx) {
SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
Map<String,?> all = sp.getAll();
if (all.isEmpty()) return "";
StringBuilder sb = new StringBuilder();
sb.append("以下是对该用户的长期记忆，执行任务时必须利用：\n");
TreeMap<String, Object> sorted = new TreeMap<>();
for (Map.Entry<String,?> e: all.entrySet()) {
sorted.put(e.getKey(), e.getValue());
}
for (Map.Entry<String, Object> e: sorted.entrySet()) {
sb.append("- ").append(e.getKey()).append("：").append(e.getValue()).append("\n");
}
sb.append("不要重复询问记忆中已有的信息。\n\n");
return sb.toString();
}
}
