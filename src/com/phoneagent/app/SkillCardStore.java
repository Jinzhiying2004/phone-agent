package com.phoneagent.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能卡存储：agent 执行完任务后自动生成的总结，拿来即用，免重复思考。
 *
 * 两套来源（优先级：user > agent）：
 * - user：用户手动创建，或一次任务后主动要求记录（/save）
 * - agent：agent 执行完任务后自主决策生成
 *
 * 两种类型：
 * - script：脚本类（shell/termux 自动化），content 为 shell 脚本
 * - skill：skill 类（任务步骤 JSON），content 为 {"steps":[...]}
 *
 * 存 files/skill_cards.json
 */
public class SkillCardStore {

    public static final String TYPE_SCRIPT = "script";
    public static final String TYPE_SKILL = "skill";
    public static final String SRC_USER = "user";
    public static final String SRC_AGENT = "agent";

    public static class Card {
        public String id;
        public String name;
        public String type;
        public String source;
        public String trigger;
        public String content;
        public String summary;
        public int useCount;
        public long updatedAt;

        public int priority() {
            return SRC_USER.equals(source) ? 10 : 5;
        }

        public String sourceLabel() {
            return SRC_USER.equals(source) ? "用户" : "自主";
        }

        public String typeLabel() {
            return TYPE_SCRIPT.equals(type) ? "脚本" : "技能";
        }
    }

    private final File file;
    private final List<Card> cards = new ArrayList<>();

    public SkillCardStore(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "skill_cards.json");
        load();
    }

    public synchronized String add(String name, String type, String source,
                                   String trigger, String content, String summary) {
        Card c = new Card();
        c.id = "card_" + System.currentTimeMillis();
        c.name = name;
        c.type = type;
        c.source = source;
        c.trigger = trigger == null ? "" : trigger;
        c.content = content == null ? "" : content;
        c.summary = summary == null ? "" : summary;
        c.useCount = 0;
        c.updatedAt = System.currentTimeMillis();
        cards.add(0, c);
        save();
        return c.id;
    }

    /** 按优先级排序：用户在前，自主在后；同级按使用次数 */
    public synchronized List<Card> listByPriority() {
        List<Card> out = new ArrayList<>(cards);
        out.sort((a, b) -> {
            int p = Integer.compare(b.priority(), a.priority());
            if (p != 0) return p;
            p = Integer.compare(b.useCount, a.useCount);
            if (p != 0) return p;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        return out;
    }

    public synchronized Card get(String id) {
        for (Card c : cards) if (c.id.equals(id)) return c;
        return null;
    }

    public synchronized void incrementUse(String id) {
        Card c = get(id);
        if (c != null) {
            c.useCount++;
            save();
        }
    }

    /** 可持续迭代更新 */
    public synchronized boolean update(String id, String name, String trigger,
                                       String content, String summary) {
        Card c = get(id);
        if (c == null) return false;
        if (name != null) c.name = name;
        if (trigger != null) c.trigger = trigger;
        if (content != null) c.content = content;
        if (summary != null) c.summary = summary;
        c.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    /**
     * v3.12 Issue 12：导出单张卡为可分享 JSON。
     * 格式：{"skill_card":1,"name":...,"type":...,"trigger":...,"content":...,"summary":...}
     */
    public synchronized String exportJson(String id) {
        Card c = get(id);
        if (c == null) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("skill_card", 1);
            o.put("name", c.name);
            o.put("type", c.type);
            o.put("trigger", c.trigger);
            o.put("content", c.content);
            o.put("summary", c.summary);
            return o.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v3.12 Issue 12：校验导入的 JSON。
     * 返回 null 表示通过，否则返回错误原因。
     * 检查：结构完整 + content 可解析 + 风险动作提示。
     */
    public static String validateImport(JSONObject o) {
        if (o == null) return "不是合法 JSON";
        if (o.optInt("skill_card", 0) != 1 && !o.has("name")) {
            return "缺少 skill_card 标记或 name 字段，可能不是技能卡 JSON";
        }
        String name = o.optString("name", "").trim();
        if (name.isEmpty()) return "name 为空";
        String type = o.optString("type", "skill");
        if (!TYPE_SKILL.equals(type) && !TYPE_SCRIPT.equals(type)) {
            return "type 非法（应为 skill/script）： " + type;
        }
        String content = o.optString("content", "");
        if (content.isEmpty()) return "content 为空";
        // content 结构检查
        try {
            if (TYPE_SKILL.equals(type)) {
                // skill 类：content 应为 {"steps":[...]} 或步骤数组
                String t = content.trim();
                if (t.startsWith("{")) {
                    JSONObject cj = new JSONObject(t);
                    if (!cj.has("steps")) return "skill 卡的 content 缺少 steps";
                } else if (t.startsWith("[")) {
                    new JSONArray(t);
                } else {
                    return "skill 卡的 content 不是 JSON 对象/数组";
                }
            }
        } catch (Exception e) {
            return "content 解析失败：" + e.getMessage();
        }
        return null; // 通过
    }

    /**
     * v3.12 Issue 12：扫描高风险动作，返回警告文本（无风险返回空串）。
     */
    public static String scanRisk(String type, String content) {
        StringBuilder warn = new StringBuilder();
        try {
            String t = content == null ? "" : content.toLowerCase();
            // 危险关键词
            String[] risky = {"\"shell\"", "\"do\":\"shell\"", "rm -rf", "pm uninstall",
                    "am force-stop", "reboot", "format", "dd if="};
            for (String k : risky) {
                if (t.contains(k)) {
                    warn.append("含高风险内容：").append(k).append("；");
                    break;
                }
            }
            // 统计步骤数
            if (TYPE_SKILL.equals(type)) {
                String tc = content.trim();
                int n = 0;
                if (tc.startsWith("{")) {
                    n = new JSONObject(tc).optJSONArray("steps").length();
                } else if (tc.startsWith("[")) {
                    n = new JSONArray(tc).length();
                }
                if (n > 30) warn.append("步骤过多（").append(n).append("步）；");
            }
        } catch (Exception ignored) {
        }
        return warn.toString();
    }

    /**
     * v3.12 Issue 12：从已校验的 JSON 导入，返回新卡 id。
     */
    public synchronized String importValidated(JSONObject o) {
        String name = o.optString("name", "未命名技能");
        String type = o.optString("type", TYPE_SKILL);
        String trigger = o.optString("trigger", "");
        String content = o.optString("content", "");
        String summary = o.optString("summary", "导入的技能卡");
        return add(name, type, SRC_USER, trigger, content, summary);
    }

    public synchronized boolean delete(String id) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id.equals(id)) {
                cards.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    /** 给 prompt 用的精简描述（按优先级） */
    public synchronized String describeForPrompt() {
        return describeCards(listByPriority());
    }

    /**
     * 按需注入：只返回与目标关键词相关的技能卡（省 token，对标 Hermes 按需加载）。
     * 无匹配时回退为全量（不让 agent 断粮），最多 5 张。
     */
    public synchronized String describeForPrompt(String goal) {
        if (goal == null || goal.trim().isEmpty()) return describeForPrompt();
        List<Card> matched = matchCards(new ArrayList<>(cards), goal);
        if (matched.isEmpty()) return describeForPrompt();
        if (matched.size() > 5) matched = matched.subList(0, 5);
        return describeCards(matched);
    }

    private static String describeCards(List<Card> list) {
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Card c : list) {
            sb.append("- [").append(c.id).append("] ").append(c.name)
                    .append("（").append(c.typeLabel()).append("·")
                    .append(c.sourceLabel());
            if (SRC_USER.equals(c.source)) sb.append("·优先");
            if (c.useCount > 0) sb.append("·已用").append(c.useCount).append("次");
            sb.append("）触发：").append(c.trigger)
                    .append(" —— ").append(c.summary).append("\n");
        }
        return sb.toString();
    }

    /**
     * 关键词匹配：目标含卡片名/触发语，或卡片关键词出现在目标里即命中。
     * 中文按二元字串（bigram）匹配，避免分词不一致导致漏召回；
     * 英文数字按词匹配。按命中数×优先级排序。纯函数，可独立测试。
     */
    static List<Card> matchCards(List<Card> all, String goal) {
        String g = goal.toLowerCase();
        java.util.Set<String> kws = keywords(goal);
        List<Card> hit = new ArrayList<>();
        final java.util.Map<Card, Integer> score = new java.util.HashMap<>();
        for (Card c : all) {
            int s = 0;
            String hay = ((c.name == null ? "" : c.name) + " "
                    + (c.trigger == null ? "" : c.trigger) + " "
                    + (c.summary == null ? "" : c.summary)).toLowerCase();
            for (String kw : kws) {
                if (hay.contains(kw)) s++;
            }
            // 中文 bigram：卡片名/触发语的每两字在目标里出现即算相关
            String nt = ((c.name == null ? "" : c.name)
                    + (c.trigger == null ? "" : c.trigger)).toLowerCase();
            for (int i = 0; i + 1 < nt.length(); i++) {
                String bi = nt.substring(i, i + 2);
                if (bi.trim().length() == 2 && g.contains(bi)) s++;
            }
            // 卡片名/触发语直接出现在目标里（如触发语"设闹钟"，目标"帮我设个闹钟"）
            if (c.name != null && c.name.length() >= 2 && g.contains(c.name.toLowerCase())) s += 2;
            if (c.trigger != null && c.trigger.length() >= 2
                    && g.contains(c.trigger.toLowerCase())) s += 2;
            if (s > 0) {
                hit.add(c);
                score.put(c, s);
            }
        }
        hit.sort((a, b) -> {
            int p = Integer.compare(score.get(b), score.get(a));
            if (p != 0) return p;
            p = Integer.compare(b.priority(), a.priority());
            if (p != 0) return p;
            return Integer.compare(b.useCount, a.useCount);
        });
        return hit;
    }

    /** 目标分词：按非中英文数字切分，保留长度≥2 的词。纯函数。 */
    static java.util.Set<String> keywords(String goal) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (goal == null) return out;
        String[] parts = goal.toLowerCase()
                .split("[^\\p{IsHan}a-z0-9]+");
        for (String p : parts) {
            if (p.length() >= 2) out.add(p);
        }
        return out;
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Card c : cards) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("type", c.type);
                o.put("source", c.source);
                o.put("trigger", c.trigger);
                o.put("content", c.content);
                o.put("summary", c.summary);
                o.put("useCount", c.useCount);
                o.put("updatedAt", c.updatedAt);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("cards", arr);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            if (!file.exists()) return;
            String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(txt).optJSONArray("cards");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Card c = new Card();
                c.id = o.optString("id");
                c.name = o.optString("name");
                c.type = o.optString("type", TYPE_SKILL);
                c.source = o.optString("source", SRC_AGENT);
                c.trigger = o.optString("trigger", "");
                c.content = o.optString("content", "");
                c.summary = o.optString("summary", "");
                c.useCount = o.optInt("useCount", 0);
                c.updatedAt = o.optLong("updatedAt", 0);
                cards.add(c);
            }
        } catch (Exception ignored) {
        }
    }
}
