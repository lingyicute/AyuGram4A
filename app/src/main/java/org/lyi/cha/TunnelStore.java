package org.lyi.cha;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 使用 SharedPreferences + JSON 持久化隧道列表 */
public class TunnelStore {
    private static final String PREFS = "tunnels";
    private static final String KEY_LIST = "list";

    private final SharedPreferences prefs;

    public TunnelStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Tunnel> load() {
        List<Tunnel> out = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Tunnel.fromJson(o));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public synchronized void save(List<Tunnel> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Tunnel t : list) arr.put(t.toJson());
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public Tunnel find(String id) {
        if (id == null) return null;
        for (Tunnel t : load()) if (id.equals(t.id)) return t;
        return null;
    }
}
