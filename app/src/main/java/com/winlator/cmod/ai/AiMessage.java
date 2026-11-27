package com.winlator.cmod.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One entry of the OpenAI-compatible "messages" array. */
public final class AiMessage {
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public final String role;
    public String content;
    /** Only set for assistant messages that asked to run tools. */
    public List<AiToolCall> toolCalls;
    /** Only set for role=tool. */
    public String toolCallId;

    public AiMessage(String role, String content) {
        this.role = role;
        this.content = content == null ? "" : content;
    }

    public static AiMessage system(String content) {
        return new AiMessage(ROLE_SYSTEM, content);
    }

    public static AiMessage user(String content) {
        return new AiMessage(ROLE_USER, content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage(ROLE_ASSISTANT, content);
    }

    public static AiMessage tool(String toolCallId, String content) {
        AiMessage message = new AiMessage(ROLE_TOOL, content);
        message.toolCallId = toolCallId;
        return message;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("role", role);
        if (ROLE_ASSISTANT.equals(role) && hasToolCalls()) {
            // OpenAI requires content to be present (null is allowed) alongside tool_calls.
            json.put("content", content.isEmpty() ? JSONObject.NULL : content);
            JSONArray array = new JSONArray();
            for (AiToolCall call : toolCalls) array.put(call.toJson());
            json.put("tool_calls", array);
        }
        else if (ROLE_TOOL.equals(role)) {
            json.put("content", content);
            if (toolCallId != null) json.put("tool_call_id", toolCallId);
        }
        else {
            json.put("content", content);
        }
        return json;
    }

    public static AiMessage fromJson(JSONObject json) {
        String role = json.optString("role", ROLE_ASSISTANT);
        String content = json.optString("content", "");
        AiMessage message = new AiMessage(role, content);
        JSONArray calls = json.optJSONArray("tool_calls");
        if (calls != null && calls.length() > 0) {
            message.toolCalls = new ArrayList<>();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call != null) message.toolCalls.add(AiToolCall.fromJson(call));
            }
        }
        return message;
    }

    /** A tool invocation requested by the model. */
    public static final class AiToolCall {
        public final String id;
        public final String name;
        public final String arguments;

        public AiToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? "{}" : arguments;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject function = new JSONObject();
            function.put("name", name);
            function.put("arguments", arguments);
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("type", "function");
            json.put("function", function);
            return json;
        }

        public static AiToolCall fromJson(JSONObject json) {
            JSONObject function = json.optJSONObject("function");
            String name = function != null ? function.optString("name", "") : "";
            String args = function != null ? function.optString("arguments", "{}") : "{}";
            String id = json.optString("id", "");
            if (id.isEmpty()) id = "call_" + Math.abs((name + args).hashCode());
            return new AiToolCall(id, name, args);
        }
    }
}
