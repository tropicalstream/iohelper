package com.iohelper.card;
import android.content.Context;
import org.json.JSONObject;
/** Desktop stub: the parser test never inspects persistence, only classification. */
public final class Store {
    public static JSONObject addTimer(Context c, int seconds, String label) {
        return new JSONObject().put("label", label == null || label.isEmpty() ? "Timer" : label);
    }
    public static JSONObject addTodo(Context c, String text) {
        return new JSONObject().put("text", text);
    }
    public static JSONObject completeTodo(Context c, String text) {
        return new JSONObject().put("text", text);
    }
    public static java.util.List<org.json.JSONObject> openTodoItems(Context c) {
        return new java.util.ArrayList<>();
    }
    public static java.util.List<String> openTodos(Context c) {
        return java.util.Arrays.asList("get bagels", "call the bank");
    }
}
