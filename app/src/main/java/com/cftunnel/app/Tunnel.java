package com.cftunnel.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** 一条 TCP 隧道配置：远端主机名 -> 127.0.0.1:port */
public class Tunnel {
    public String id;
    public String name;
    public String hostname;
    public int port;

    public Tunnel() {
        id = UUID.randomUUID().toString();
    }

    public String localAddress() {
        return "127.0.0.1:" + port;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("hostname", hostname);
        o.put("port", port);
        return o;
    }

    public static Tunnel fromJson(JSONObject o) {
        Tunnel t = new Tunnel();
        t.id = o.optString("id", t.id);
        t.name = o.optString("name", "");
        t.hostname = o.optString("hostname", "");
        t.port = o.optInt("port", 0);
        return t;
    }
}
