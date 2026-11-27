package com.winlator.cmod.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal OpenAI-compatible chat client.
 *
 * Talks to {@code POST <baseUrl>/chat/completions} so it works with OpenAI, DeepSeek, Moonshot,
 * Zhipu, SiliconFlow, OpenRouter, Ollama, LM Studio, vLLM and anything else that copied that API.
 * OkHttp is used directly rather than Retrofit because the URL is user supplied and the streaming
 * response is SSE, which Retrofit models badly.
 */
public final class AiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;

    public AiClient() {
        http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // Reasoning models can take a long time before the first token arrives.
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /** Callback for one round trip. Exactly one of the two methods is called, on a worker thread. */
    public interface Callback {
        void onResult(AiMessage message);
        void onError(String message);
    }

    public Call newCall(AiConfigSnapshot config, List<AiMessage> messages, boolean allowCommands,
                        boolean stream, Callback callback) {
        String url = config.baseUrl + "/chat/completions";
        JSONObject body = buildBody(config, messages, allowCommands, stream);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .header("Content-Type", "application/json");
        if (!config.apiKey.isEmpty()) builder.header("Authorization", "Bearer " + config.apiKey);

        Call call = http.newCall(builder.build());
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                if (c.isCanceled()) return;
                callback.onError(describeNetworkFailure(e, url));
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        callback.onError(describeHttpError(r));
                        return;
                    }
                    String payload = r.body() != null ? r.body().string() : "";
                    AiMessage message = stream ? parseStreaming(payload, callback) : parseWhole(payload);
                    if (message == null) callback.onError("Empty response from the model endpoint.");
                    else callback.onResult(message);
                }
                catch (Exception e) {
                    callback.onError("Failed to read the response: " + e.getMessage());
                }
            }
        });
        return call;
    }

    /**
     * Cheap reachability check for the settings screen: sends one short prompt with no tools and no
     * streaming, so a misconfigured key or URL fails fast with a readable reason.
     */
    public static void ping(AiConfigSnapshot config, Callback callback) {
        if (config.baseUrl.isEmpty()) {
            callback.onError("Base URL 是空的。");
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("model", config.model);
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", "ping");
            messages.put(message);
            body.put("messages", messages);
            body.put("max_tokens", 1);
            body.put("stream", false);
        }
        catch (JSONException e) {
            callback.onError("无法构造请求。");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Request.Builder builder = new Request.Builder()
                .url(config.baseUrl + "/chat/completions")
                .post(RequestBody.create(body.toString(), JSON))
                .header("Content-Type", "application/json");
        if (!config.apiKey.isEmpty()) builder.header("Authorization", "Bearer " + config.apiKey);

        client.newCall(builder.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (r.isSuccessful()) callback.onResult(AiMessage.assistant("ok"));
                    else callback.onError(new AiClient().describeHttpError(r));
                }
                catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    private JSONObject buildBody(AiConfigSnapshot config, List<AiMessage> messages,
                                 boolean allowCommands, boolean stream) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", config.model);
            JSONArray array = new JSONArray();
            for (AiMessage message : messages) array.put(message.toJson());
            body.put("messages", array);
            body.put("tools", AiTools.definitions(allowCommands));
            body.put("tool_choice", "auto");
            body.put("stream", stream);
            body.put("temperature", 0.3);
        }
        catch (JSONException e) {
            // Only reachable if we put something unserialisable in, which we do not.
            throw new IllegalStateException(e);
        }
        return body;
    }

    /** Non-streaming response: choices[0].message. */
    private AiMessage parseWhole(String payload) throws JSONException {
        JSONObject root = new JSONObject(payload);
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", error.toString());
            throw new JSONException("Provider error: " + message);
        }
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return null;
        JSONObject message = choice.optJSONObject("message");
        if (message == null) return null;
        return AiMessage.fromJson(message);
    }

    /**
     * SSE stream: lines of "data: {...}", terminated by "data: [DONE]". Deltas are concatenated into
     * one message. Streaming matters here because a tool-calling round on a slow phone connection can
     * otherwise sit silent for a minute and look broken.
     */
    private AiMessage parseStreaming(String payload, Callback callback) throws JSONException {
        StringBuilder content = new StringBuilder();
        List<ToolCallAccumulator> calls = new ArrayList<>();

        for (String rawLine : payload.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || !line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;

            JSONObject chunk;
            try {
                chunk = new JSONObject(data);
            }
            catch (JSONException ignored) {
                continue; // keep-alive comment or a provider quirk
            }

            JSONObject error = chunk.optJSONObject("error");
            if (error != null) throw new JSONException("Provider error: " + error.optString("message", error.toString()));

            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) continue;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) continue;

            String piece = delta.optString("content", "");
            if (!piece.isEmpty()) content.append(piece);

            JSONArray toolDeltas = delta.optJSONArray("tool_calls");
            if (toolDeltas != null) mergeToolDeltas(calls, toolDeltas);
        }

        if (content.length() == 0 && calls.isEmpty()) return null;

        AiMessage message = AiMessage.assistant(content.toString());
        if (!calls.isEmpty()) {
            List<AiMessage.AiToolCall> resolved = new ArrayList<>();
            for (ToolCallAccumulator accumulator : calls) {
                AiMessage.AiToolCall call = accumulator.toToolCall();
                if (!call.name.isEmpty()) resolved.add(call);
            }
            if (!resolved.isEmpty()) message.toolCalls = resolved;
        }
        return message;
    }

    /**
     * Streaming tool calls arrive in fragments: the first delta carries id and function name, later
     * deltas only carry pieces of the arguments string, matched by index.
     */
    private void mergeToolDeltas(List<ToolCallAccumulator> calls, JSONArray deltas) {
        for (int i = 0; i < deltas.length(); i++) {
            JSONObject delta = deltas.optJSONObject(i);
            if (delta == null) continue;
            int index = delta.optInt("index", calls.size());
            while (calls.size() <= index) calls.add(new ToolCallAccumulator());

            ToolCallAccumulator target = calls.get(index);
            String id = delta.optString("id", "");
            if (!id.isEmpty()) target.id = id;
            JSONObject function = delta.optJSONObject("function");
            if (function != null) {
                String name = function.optString("name", "");
                if (!name.isEmpty()) target.name = name;
                String args = function.optString("arguments", "");
                if (!args.isEmpty()) target.arguments.append(args);
            }
        }
    }

    /** Accumulates the fragments of one streamed tool call. */
    private static final class ToolCallAccumulator {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();

        AiMessage.AiToolCall toToolCall() {
            String args = arguments.length() == 0 ? "{}" : arguments.toString();
            String resolvedId = id.isEmpty()
                    ? "call_" + Math.abs((name + args).hashCode())
                    : id;
            return new AiMessage.AiToolCall(resolvedId, name, args);
        }
    }

    private String describeHttpError(Response response) {
        String detail = "";
        try {
            if (response.body() != null) {
                String text = response.body().string();
                if (!TextUtils.isEmpty(text)) {
                    JSONObject json = new JSONObject(text);
                    JSONObject error = json.optJSONObject("error");
                    detail = error != null ? error.optString("message", text) : text;
                }
            }
        }
        catch (Exception ignored) {}
        if (detail.length() > 500) detail = detail.substring(0, 500) + "…";

        int code = response.code();
        String hint;
        if (code == 401 || code == 403) hint = "API Key 无效或没有权限，请检查设置里的 Key。";
        else if (code == 404) hint = "接口地址不对，请检查 Base URL（应形如 https://api.xxx.com/v1）。";
        else if (code == 429) hint = "请求太频繁或额度用完了。";
        else if (code >= 500) hint = "服务端出错，稍后再试或换个模型。";
        else hint = "HTTP " + code;

        return hint + (detail.isEmpty() ? "" : "\n" + detail);
    }

    private String describeNetworkFailure(IOException e, String url) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (reason.contains("Unable to resolve host") || reason.contains("UnknownHost")) {
            return "无法解析域名，请检查 Base URL 和网络连接。\n" + url;
        }
        if (reason.contains("timeout") || reason.contains("timed out")) {
            return "请求超时。可能是网络慢或模型响应太久，可以换个更快的模型。";
        }
        if (reason.contains("SSL") || reason.contains("Certificate")) {
            return "TLS 握手失败，请确认 Base URL 是 https 且证书有效。\n" + reason;
        }
        return "网络错误：" + reason;
    }
}
