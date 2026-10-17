package com.phoneagent.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Telegram Bot 配置存储（App 私有 SharedPreferences）。
 * token 只存本地、只发往 api.telegram.org，界面脱敏显示，绝不打日志。
 * owner 为绑定的主人 chatId（/start 首次绑定），只接受主人的指令。
 */
public class TgStore {

    private final SharedPreferences pref;

    public TgStore(Context ctx) {
        pref = ctx.getSharedPreferences("tgbot", Context.MODE_PRIVATE);
    }

    public void saveToken(String token) {
        pref.edit().putString("token", token == null ? "" : token.trim()).apply();
    }

    public String getToken() {
        return pref.getString("token", "");
    }

    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    public String getMaskedToken() {
        String t = getToken();
        if (t.length() <= 8) return "****";
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }

    public long getOwner() {
        return pref.getLong("owner", 0);
    }

    public boolean hasOwner() {
        return getOwner() != 0;
    }

    public void bindOwner(long chatId) {
        pref.edit().putLong("owner", chatId).apply();
    }

    public void unbindOwner() {
        pref.edit().remove("owner").apply();
    }
}
