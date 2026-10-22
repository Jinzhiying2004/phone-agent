package com.phoneagent.app;

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
 * v3.55 认知库（记忆分层·长期记忆的唯一形态）。
 * 设计稿：目标工作区 hidden_files/v351-design.md 第二节 2.1；
 * 规格：~/workspace/learning-loop-SPEC.md 第四节（记忆分层 doctrine）。
 *
 * 纯 Java：文件 IO 走 java.io.File（调用方传 ctx.getFilesDir()），
 * 无 Android 依赖，可在 JVM 直接单测。文件格式：
 * cognition.json = {"items":[{"id":"c-0001","kind":"..","text":"..",
 *   "src":"..","ts":..,"votes":1,"status":"active|draft","pkg":".."}]}
 *
 * 纪律（用户亲定）：
 * - 长期记忆只存认知（对用户的认知 + 对自身的认知），任务执行流水
 *   禁止入库——流水走 Distiller 蒸馏，蒸馏不出就丢。
 * - 只有 status=active 的条目参与注入；draft 只做投票计数。
 * - 转正条件：votes>=2（同类候选再出现自动 +1），或用户明确认可
 *   （confirm）。src=user_seed（用户亲定/种子迁移）直接 active。
 */
public class CognitionStore {

    public static final String FILE_NAME = "cognition.json";
    public static final int MAX_ITEMS = 500;

    /** 条目类型（设计稿 2.1 八类） */
    public static final String KIND_USER_DICT = "user_dict";
    public static final String KIND_USER_PREF = "user_pref";
    public static final String KIND_USER_CORRECTION = "user_correction";
    public static final String KIND_SELF_CAP = "self_cap";
    public static final String KIND_SELF_DEVICE = "self_device";
    public static final String KIND_SELF_LESSION = "self_lesson";
    public static final String KIND_ICON_WORD = "icon_word";
    public static final String KIND_UI_MAP = "ui_map";

    /** 来源 */
    public static final String SRC_USER_SEED = "user_seed";
    public static final String SRC_TASK_DISTILL = "task_distill";
    public static final String SRC_NIGHT_REVIEW = "night_review";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DRAFT = "draft";

    public static class Item {
        public String id = "";
        public String kind = "";
        public String text = "";
        public String src = "";
        public long ts = 0L;
        public int votes = 1;
        public String status = STATUS_DRAFT;
        /** 仅 ui_map 用：App 包名 */
        public String pkg = "";

        public Item() {
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int nextId = 1;

    /** 从目录加载；文件缺失/解析失败返回空库（不抛异常） */
    public static CognitionStore load(File dir) {
        CognitionStore s = new CognitionStore();
        try {
            if (dir == null) return s;
            File f = new File(dir, FILE_NAME);
            if (!f.exists()) return s;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length
                    && (r = in.read(buf, off, buf.length - off)) > 0) {
                off += r;
            }
            in.close();
            if (off == 0) return s;
            JSONObject root = new JSONObject(new String(buf, 0, off, "UTF-8"));
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return s;
            for (int i = 0; i < arr.length() && s.items.size() < MAX_ITEMS; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String kind = o.optString("kind", "").trim();
                String text = o.optString("text", "").trim();
                if (!isKind(kind) || text.isEmpty()) continue;
                Item it = new Item();
                it.id = o.optString("id", "").trim();
                it.kind = kind;
                it.text = text;
                it.src = o.optString("src", "").trim();
                it.ts = o.optLong("ts", 0L);
                it.votes = Math.max(1, o.optInt("votes", 1));
                it.status = STATUS_ACTIVE.equals(o.optString("status", ""))
                        ? STATUS_ACTIVE : STATUS_DRAFT;
                it.pkg = KIND_UI_MAP.equals(kind)
                        ? o.optString("pkg", "").trim() : "";
                s.items.add(it);
                int n = parseIdNum(it.id);
                if (n >= s.nextId) s.nextId = n + 1;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 持久化到目录 */
    public synchronized void save(File dir) {
        try {
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("kind", it.kind);
                o.put("text", it.text);
                o.put("src", it.src);
                o.put("ts", it.ts);
                o.put("votes", it.votes);
                o.put("status", it.status);
                if (!it.pkg.isEmpty()) o.put("pkg", it.pkg);
                arr.put(o);
            }
            root.put("items", arr);
            File f = new File(dir, FILE_NAME);
            FileOutputStream out = new FileOutputStream(f);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 新增认知条目。非法 kind/空 text 拒绝并返回空串。
     * src=user_seed 直接 active（用户亲定即认可）；其余 draft 起步。
     * pkg 只对 ui_map 有意义。返回条目 id。
     */
    public synchronized String put(String kind, String text, String src,
            String pkg) {
        if (!isKind(kind) || text == null || text.trim().isEmpty()) return "";
        Item it = new Item();
        it.id = "c-" + String.format("%04d", nextId++);
        it.kind = kind;
        it.text = text.trim();
        it.src = src == null ? "" : src.trim();
        it.ts = System.currentTimeMillis();
        it.votes = 1;
        it.status = SRC_USER_SEED.equals(it.src)
                ? STATUS_ACTIVE : STATUS_DRAFT;
        it.pkg = KIND_UI_MAP.equals(kind) && pkg != null ? pkg.trim() : "";
        items.add(it);
        while (items.size() > MAX_ITEMS) items.remove(0);
        return it.id;
    }

    /** 投票：同类候选再出现调用；votes>=2 自动转正。返回转正后状态 */
    public synchronized String vote(String id) {
        Item it = find(id);
        if (it == null) return "";
        it.votes++;
        if (it.votes >= 2) it.status = STATUS_ACTIVE;
        return it.status;
    }

    /** 用户明确认可 → 直接转正 */
    public synchronized boolean confirm(String id) {
        Item it = find(id);
        if (it == null) return false;
        it.status = STATUS_ACTIVE;
        return true;
    }

    /** 删除条目（认知条目留删除能力，供后续修卡机制复用） */
    public synchronized boolean remove(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return items.size();
    }

    /** 全部条目（含 draft，供 Distiller 去重投票遍历） */
    public synchronized List<Item> all() {
        return new ArrayList<>(items);
    }

    public synchronized List<Item> active() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)) out.add(it);
        }
        return out;
    }

    /**
     * 相关认知注入：对 active 条目做 bigram Jaccard>=0.15 取最相关 2 条
     *（算法复用 SharedMemory.relevantExperience 一套口径）。
     * "内部参考"头保留：注入只进任务上下文，禁入最终答案。
     */
    public synchronized String relevant(String goal) {
        if (goal == null || goal.isEmpty()) return "";
        Set<String> mine = bigrams(goal);
        if (mine.isEmpty()) return "";
        List<double[]> scored = new ArrayList<>();
        List<Item> act = active();
        for (int i = 0; i < act.size(); i++) {
            Item it = act.get(i);
            Set<String> theirs = bigrams(it.text);
            if (theirs.isEmpty()) continue;
            int inter = 0;
            for (String g : mine) if (theirs.contains(g)) inter++;
            double j = (double) inter
                    / (mine.size() + theirs.size() - inter);
            if (j >= 0.15) scored.add(new double[]{j, i});
        }
        if (scored.isEmpty()) return "";
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder();
        sb.append("[内部参考·勿向用户复述：以下为长期认知库中的相关认知，"
                + "仅供本次任务规划借鉴；与本次任务无关的条目请忽略，"
                + "最终回答用户时不要引用或复述这段内容。]\n");
        int n = Math.min(2, scored.size());
        for (int k = 0; k < n; k++) {
            Item it = act.get((int) scored.get(k)[1]);
            sb.append("- 「").append(kindName(it.kind)).append("」")
                    .append(trim(it.text, 60)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 常驻注入：active 的 user_dict 条目（供步骤 3 接管 MasterDict 注入） */
    public synchronized String dictLines() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_USER_DICT.equals(it.kind)) {
                sb.append("- ").append(it.text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 钉住块用紧凑行：k=短义；…（供步骤 3 接管 MasterDict.describeShort） */
    public synchronized String dictShort() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Item it : items) {
            if (!STATUS_ACTIVE.equals(it.status)
                    || !KIND_USER_DICT.equals(it.kind)) {
                continue;
            }
            String key = keyOf(it.text);
            if (key.isEmpty()) continue;
            int i = it.text.indexOf('=');
            String v = i < 0 ? "" : it.text.substring(i + 1).trim();
            if (!first) sb.append("；");
            first = false;
            sb.append(key).append("=").append(trim(v, 30));
        }
        return sb.toString();
    }

    /** 图标词典：active 的 icon_word 条目全文（供视觉/规划提示词注入） */
    public synchronized String iconWords() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_ICON_WORD.equals(it.kind)) {
                sb.append("- ").append(it.text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 界面地图：某包名的 active ui_map 条目 */
    public synchronized List<Item> uiMap(String pkg) {
        List<Item> out = new ArrayList<>();
        if (pkg == null) return out;
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_UI_MAP.equals(it.kind)
                    && pkg.equals(it.pkg)) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * 主人词典种子迁移（步骤 3 接线：AgentLoop 任务入口调用）：
     * MasterDict 的用户亲定种子（src=user/user_seed）转为 user_dict 条目
     * （src=user_seed，直接 active），text 格式为 k=v。按 key（= 前部分）
     * 幂等：已存在同 key 条目时，若文本变化则更新（种子改版=改条目，
     * 不改代码，与 MasterDict.load 的种子覆盖语义一致），否则跳过；
     * 返回新增+更新的条目数。
     */
    public synchronized int importSeeds(MasterDict dict) {
        if (dict == null) return 0;
        int n = 0;
        for (MasterDict.Item si : dict.all()) {
            if (!SRC_USER_SEED.equals(si.src)
                    && !"user".equals(si.src)) {
                continue;
            }
            String key = si.k == null ? "" : si.k.trim();
            if (key.isEmpty()) continue;
            String text = key + "="
                    + (si.v == null ? "" : si.v.trim());
            Item found = null;
            for (Item it : items) {
                if (KIND_USER_DICT.equals(it.kind)
                        && key.equals(keyOf(it.text))) {
                    found = it;
                    break;
                }
            }
            if (found == null) {
                put(KIND_USER_DICT, text, SRC_USER_SEED, "");
                n++;
            } else if (!text.equals(found.text)) {
                found.text = text;
                found.src = SRC_USER_SEED;
                found.status = STATUS_ACTIVE;
                found.ts = System.currentTimeMillis();
                n++;
            }
        }
        return n;
    }

    /** user_dict 文本 "k=v" 的 key 部分（= 前）；无 = 时取全文 */
    private static String keyOf(String text) {
        if (text == null) return "";
        int i = text.indexOf('=');
        return (i < 0 ? text : text.substring(0, i)).trim();
    }

    // ---------------- 内部 ----------------

    private Item find(String id) {
        if (id == null) return null;
        for (Item it : items) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    private static boolean isKind(String k) {
        return KIND_USER_DICT.equals(k) || KIND_USER_PREF.equals(k)
                || KIND_USER_CORRECTION.equals(k) || KIND_SELF_CAP.equals(k)
                || KIND_SELF_DEVICE.equals(k) || KIND_SELF_LESSION.equals(k)
                || KIND_ICON_WORD.equals(k) || KIND_UI_MAP.equals(k);
    }

    private static String kindName(String k) {
        if (KIND_USER_DICT.equals(k)) return "用户词典";
        if (KIND_USER_PREF.equals(k)) return "用户偏好";
        if (KIND_USER_CORRECTION.equals(k)) return "用户纠正";
        if (KIND_SELF_CAP.equals(k)) return "自身能力";
        if (KIND_SELF_DEVICE.equals(k)) return "设备认知";
        if (KIND_SELF_LESSION.equals(k)) return "自身教训";
        if (KIND_ICON_WORD.equals(k)) return "图标词典";
        if (KIND_UI_MAP.equals(k)) return "界面地图";
        return k;
    }

    private static int parseIdNum(String id) {
        try {
            if (id != null && id.startsWith("c-")) {
                return Integer.parseInt(id.substring(2));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 与 SharedMemory.bigrams 同口径（去空白与标点后按字二元组） */
    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String t = s.replaceAll("[\\s\\p{P}]", "");
        if (t.length() < 2) {
            if (!t.isEmpty()) out.add(t);
            return out;
        }
        for (int i = 0; i + 2 <= t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
