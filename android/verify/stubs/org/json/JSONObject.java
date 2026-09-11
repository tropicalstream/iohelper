package org.json;
import java.util.HashMap;
import java.util.Map;
/** Desktop stub: just enough JSONObject for the parser test. */
public class JSONObject {
    private final Map<String, Object> map = new HashMap<>();
    public JSONObject put(String k, Object v) { map.put(k, v); return this; }
    public String optString(String k) { Object v = map.get(k); return v == null ? "" : String.valueOf(v); }
    public String optString(String k, String d) { Object v = map.get(k); return v == null ? d : String.valueOf(v); }
    public boolean optBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
}
