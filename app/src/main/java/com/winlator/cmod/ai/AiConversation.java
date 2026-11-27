package com.winlator.cmod.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/**
 * Drives one conversation: sends the history, executes whatever tools the model asks for, sends the
 * results back, and repeats until the model answers in prose.
 *
 * All callbacks are delivered on the main thread, so the UI can update views without posting.
 */
public final class AiConversation {

    /** How many past messages to keep. Tool chatter is dropped first when trimming. */
    private static final int MAX_HISTORY = 40;

    public interface Listener {
        /** The model produced user-visible text. */
        void onAssistantMessage(String text);
        /** A tool is about to run; {@code label} is human readable. */
        void onToolStarted(String label);
        void onToolFinished(String label, boolean ok);
        void onError(String message);
        void onBusyChanged(boolean busy);
    }

    private final Context context;
    private final AiToolExecutor executor;
    private final AiClient client = new AiClient();
    private final List<AiMessage> history = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AiConfigSnapshot config;
    private final AiToolExecutor.Session session;

    private Listener listener;
    private Call activeCall;
    private boolean busy;
    private int toolRounds;
    private String systemPrompt;

    public AiConversation(AiToolExecutor.Session session) {
        this.session = session;
        this.context = session.getContext();
        this.executor = new AiToolExecutor(session);
        this.config = AiConfigSnapshot.from(context);
        this.systemPrompt = AiDiagnostics.buildSystemPrompt(context, config.allowCommands);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isBusy() {
        return busy;
    }

    public List<AiMessage> getHistory() {
        return history;
    }

    /** The live container bindings, so callers can build things like the diagnostic bundle. */
    public AiToolExecutor.Session getSession() {
        return session;
    }

    /**
     * Seeds the conversation with the auto-collected diagnostic context. Called once when the chat
     * opens, so the user can just type "闪退" and the model already has the logs.
     */
    public void primeWithContext() {
        if (!history.isEmpty()) return;
        Container container = session.getContainer();
        ImageFs imageFs = session.getImageFs();
        String block = AiDiagnostics.buildContextBlock(context, container, imageFs);
        history.add(AiMessage.user(block));
    }

    public void send(String userText) {
        if (busy) return;
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) return;

        if (history.isEmpty() && AiConfig.isAutoDiagnose(context)) primeWithContext();
        history.add(AiMessage.user(text));
        toolRounds = 0;
        trimHistory();
        requestCompletion();
    }

    /** Re-runs the last user turn after a failure, without duplicating it. */
    public void retry() {
        if (busy || history.isEmpty()) return;
        toolRounds = 0;
        requestCompletion();
    }

    public void cancel() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
        setBusy(false);
    }

    public void reset() {
        cancel();
        history.clear();
    }

    private void requestCompletion() {
        if (config.apiKey.isEmpty()) {
            emitError("还没有填写 API Key。打开 设置 → AI 助手 填好 Key 再试。");
            return;
        }

        setBusy(true);
        List<AiMessage> payload = new ArrayList<>();
        payload.add(AiMessage.system(systemPrompt));
        payload.addAll(history);

        activeCall = client.newCall(config, payload, config.allowCommands, true, new AiClient.Callback() {
            @Override
            public void onResult(AiMessage message) {
                activeCall = null;
                main.post(() -> handleModelReply(message));
            }

            @Override
            public void onError(String error) {
                activeCall = null;
                main.post(() -> {
                    setBusy(false);
                    emitError(error);
                });
            }
        });
    }

    private void handleModelReply(AiMessage message) {
        if (message == null) {
            setBusy(false);
            emitError("模型没有返回内容。");
            return;
        }

        history.add(message);

        if (!message.hasToolCalls()) {
            setBusy(false);
            String text = message.content == null ? "" : message.content.trim();
            if (text.isEmpty()) emitError("模型返回了空回复。");
            else if (listener != null) listener.onAssistantMessage(text);
            return;
        }

        // The model wants to inspect something before answering.
        if (message.content != null && !message.content.trim().isEmpty() && listener != null) {
            listener.onAssistantMessage(message.content.trim());
        }

        toolRounds++;
        if (toolRounds > AiConfig.getMaxToolRounds(context)) {
            setBusy(false);
            emitError("诊断步骤太多，已停止。可以换个问法，或把日志发出来一起看。");
            return;
        }

        executeToolCalls(message.toolCalls, 0);
    }

    /** Tools run sequentially on a worker thread; each result is appended before the next call. */
    private void executeToolCalls(List<AiMessage.AiToolCall> calls, int index) {
        if (index >= calls.size()) {
            trimHistory();
            requestCompletion();
            return;
        }

        AiMessage.AiToolCall call = calls.get(index);
        String label = describeTool(call.name);
        if (listener != null) listener.onToolStarted(label);

        new Thread(() -> {
            String result = executor.execute(call.name, call.arguments);
            boolean ok = !result.startsWith("ERROR") && !result.startsWith("REJECTED");
            main.post(() -> {
                if (listener != null) listener.onToolFinished(label, ok);
                history.add(AiMessage.tool(call.id, result));
                executeToolCalls(calls, index + 1);
            });
        }, "ai-tool").start();
    }

    private String describeTool(String name) {
        if (name == null) return "工具";
        switch (name) {
            case AiTools.TOOL_CONTAINER_CONFIG: return "读取容器配置";
            case AiTools.TOOL_DEVICE_INFO: return "读取设备信息";
            case AiTools.TOOL_COMPONENTS: return "检查组件与 DLL";
            case AiTools.TOOL_LIST_LOGS: return "查找日志文件";
            case AiTools.TOOL_READ_LOG: return "读取日志";
            case AiTools.TOOL_PROCESSES: return "检查运行进程";
            case AiTools.TOOL_PREFIX_PROGRAMS: return "扫描容器内程序";
            case AiTools.TOOL_RUN_COMMAND: return "执行只读命令";
            default: return name;
        }
    }

    /**
     * Keeps the request from growing without bound. Tool results are the bulkiest and least useful
     * once consumed, so they go first, but a tool result is never dropped without its request.
     */
    private void trimHistory() {
        if (history.size() <= MAX_HISTORY) return;

        List<AiMessage> trimmed = new ArrayList<>();
        // Always keep the leading context message.
        trimmed.add(history.get(0));
        for (int i = history.size() - (MAX_HISTORY - 1); i < history.size(); i++) {
            AiMessage message = history.get(i);
            // Never start the kept window with an orphaned tool result.
            if (trimmed.size() == 1 && AiMessage.ROLE_TOOL.equals(message.role)) continue;
            trimmed.add(message);
        }
        history.clear();
        history.addAll(trimmed);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (listener != null) listener.onBusyChanged(value);
    }

    private void emitError(String message) {
        if (listener != null) listener.onError(message);
    }

    /** Builds a {@link AiToolExecutor.Session} from a live activity's components. */
    public static AiToolExecutor.Session sessionFor(Context context, Container container,
                                                    ImageFs imageFs, EnvVars envVars) {
        return new AiToolExecutor.Session() {
            @Override
            public Context getContext() {
                return context;
            }

            @Override
            public Container getContainer() {
                return container;
            }

            @Override
            public ImageFs getImageFs() {
                return imageFs;
            }

            @Override
            public EnvVars getSessionEnvVars() {
                return envVars != null ? envVars : new EnvVars();
            }

            @Override
            public File getWorkingDir() {
                return imageFs != null ? imageFs.getRootDir() : null;
            }
        };
    }
}
