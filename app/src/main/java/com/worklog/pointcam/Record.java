package com.worklog.pointcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一張已拍照片的紀錄：存在哪裡、哪一天、第幾點、幾點拍的。 */
final class Record {
    static final String POINT = "point";
    static final String RESULT = "result";

    String uri;
    String kind = POINT; // 點號照片或成果圖
    String day;   // yyyy-MM-dd
    int point;    // 點號；成果圖則為當天第幾張
    String time;  // HH:mm:ss
    long ts;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("uri", uri).put("kind", kind).put("day", day).put("point", point)
                .put("time", time).put("ts", ts);
    }

    static Record fromJson(JSONObject o) {
        Record r = new Record();
        r.uri = o.optString("uri");
        r.kind = o.optString("kind", POINT);
        r.day = o.optString("day");
        r.point = o.optInt("point");
        r.time = o.optString("time");
        r.ts = o.optLong("ts");
        return r;
    }

    boolean isResult() { return RESULT.equals(kind); }

    static List<Record> parseList(String json) {
        List<Record> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    static String toJsonList(List<Record> list) {
        JSONArray a = new JSONArray();
        for (Record r : list) {
            try {
                a.put(r.toJson());
            } catch (JSONException ignored) {
            }
        }
        return a.toString();
    }
}
