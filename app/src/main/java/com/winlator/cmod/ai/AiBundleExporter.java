package com.winlator.cmod.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.logs.LogFiles;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs everything a helper would ask for into one zip: device and container description, the log
 * files of this session, and the chat transcript if there is one.
 *
 * The logs on disk are already redacted line by line by {@link LogFiles#redact(String)} when they are
 * written, so this class copies them verbatim instead of trying to redact again — a second pass over
 * already-redacted text would only mangle it. The files it writes itself (summary, transcript) are
 * pushed through the same redactor.
 */
public final class AiBundleExporter {

    /** Written first so a reader opening the zip sees the human-readable part without unpacking logs. */
    private static final String ENTRY_SUMMARY = "summary.txt";
    private static final String ENTRY_TRANSCRIPT = "chat.txt";
    private static final String LOG_DIR = "logs/";

    private static final int MAX_TRANSCRIPT_CHARS = 400_000;

    private AiBundleExporter() {}

    /** Builds the zip on a worker thread and reports the result there; hop to the main thread to touch UI. */
    public static void exportAsync(Context context, Container container, ImageFs imageFs,
                                   List<AiMessage> history, Callback onDone) {
        Context app = context.getApplicationContext();
        // The conversation keeps mutating its history on the main thread; snapshot before leaving it.
        List<AiMessage> snapshot = history == null ? null : new ArrayList<>(history);
        new Thread(() -> {
            try {
                onDone.onDone(build(app, container, imageFs, snapshot), null);
            }
            catch (Exception e) {
                onDone.onDone(null, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "ai-bundle").start();
    }

    /** Result callback; exactly one of the two arguments is non-null. */
    public interface Callback {
        void onDone(File zip, String error);
    }

    private static File build(Context context, Container container, ImageFs imageFs,
                              List<AiMessage> history) throws Exception {
        File dir = new File(context.getCacheDir(), "ai-bundles");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");
        File zip = new File(dir, "winlator_ai_" + LogFiles.timestamp(System.currentTimeMillis()) + ".zip");

        int[] entries = {0};
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            writeEntry(zos, ENTRY_SUMMARY, buildSummary(context, container, imageFs), entries);
            if (history != null && !history.isEmpty()) {
                writeEntry(zos, ENTRY_TRANSCRIPT, buildTranscript(history), entries);
            }
            copyLogs(context, zos, entries);
        }

        // Only the generated summary and no log at all means there is nothing worth reading.
        if (entries[0] <= 1) {
            zip.delete();
            throw new Exception("日志目录为空，没有可打包的内容");
        }
        return zip;
    }

    private static String buildSummary(Context context, Container container, ImageFs imageFs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Winlator AI 诊断包\n");
        sb.append("生成时间: ").append(LogFiles.timestamp(System.currentTimeMillis())).append("\n\n");

        sb.append("== 设备 ==\n");
        sb.append(AiDiagnostics.deviceSummary(context)).append("\n\n");

        sb.append("== 容器 ==\n");
        if (container != null) sb.append(AiDiagnostics.containerSummary(container));
        else sb.append("(未进入容器)");
        sb.append("\n\n");

        sb.append("== 镜像 ==\n");
        if (imageFs != null) sb.append(AiDiagnostics.imageFsSummary(imageFs));
        else sb.append("(镜像未就绪)");
        sb.append("\n");

        File logRoot = LogFiles.root(context);
        sb.append("\n== 日志位置 ==\n").append(logRoot.getAbsolutePath()).append("\n");
        return redactBlock(sb.toString());
    }

    /** Newest run of every group, so the archive stays small but still covers other games. */
    private static void copyLogs(Context context, ZipOutputStream zos, int[] entries) {
        List<LogFiles.Group> groups = LogFiles.scan(context);
        for (LogFiles.Group group : groups) {
            List<LogFiles.Run> runs = LogFiles.runs(group.dir);
            if (runs.isEmpty()) continue;
            LogFiles.Run newest = runs.get(0);

            // The catch-all group points at the log root itself, so it has no folder of its own.
            String name = group.dir.equals(LogFiles.root(context)) ? "_all" : LogFiles.folderName(group.name);
            String prefix = LOG_DIR + name + "/";
            for (File file : LogFiles.filesIn(newest.dir)) {
                // Already redacted when written; copy as-is.
                copyInto(zos, prefix + file.getName(), file, entries);
            }
        }
    }

    private static String buildTranscript(List<AiMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI 对话记录\n\n");
        int used = 0;
        for (AiMessage message : history) {
            if (message == null || AiMessage.ROLE_SYSTEM.equals(message.role)) continue;
            String text = message.content == null ? "" : message.content.trim();
            if (text.isEmpty() && (message.toolCalls == null || message.toolCalls.isEmpty())) continue;
            if (used >= MAX_TRANSCRIPT_CHARS) {
                sb.append("\n[对话过长，其余内容已省略]\n");
                break;
            }

            String label;
            if (AiMessage.ROLE_USER.equals(message.role)) label = "用户";
            else if (AiMessage.ROLE_ASSISTANT.equals(message.role)) label = "助手";
            else label = "工具";

            sb.append("[").append(label).append("]\n");
            if (!text.isEmpty()) sb.append(text).append("\n");
            if (message.toolCalls != null) {
                for (AiMessage.AiToolCall call : message.toolCalls) {
                    sb.append("→ ").append(call.name).append(" ").append(call.arguments).append("\n");
                }
            }
            sb.append("\n");
            used += text.length();
        }
        return redactBlock(sb.toString());
    }

    /** {@link LogFiles#redact(String)} is written for one line at a time, so apply it per line. */
    private static String redactBlock(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (String line : text.split("\n", -1)) {
            if (out.length() > 0) out.append('\n');
            out.append(LogFiles.redact(line));
        }
        return out.toString();
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content, int[] entries) throws Exception {
        if (content == null || content.isEmpty()) return;
        zos.putNextEntry(new ZipEntry(name));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes, 0, bytes.length);
        zos.closeEntry();
        entries[0]++;
    }

    private static void copyInto(ZipOutputStream zos, String name, File file, int[] entries) {
        if (file == null || !file.isFile() || file.length() == 0) return;
        try (FileInputStream in = new FileInputStream(file)) {
            zos.putNextEntry(new ZipEntry(name));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) zos.write(buffer, 0, read);
            zos.closeEntry();
            entries[0]++;
        }
        catch (Exception e) {
            // One unreadable file must not sink the whole archive.
            try { zos.closeEntry(); } catch (Exception ignored) {}
        }
    }

    /** Hands the zip to the system share sheet. */
    public static void share(Context context, File zip) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".tileprovider", zip);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Winlator AI 诊断包")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(send, "分享诊断包"));
        }
        catch (Exception e) {
            AppUtils.showToast(context, "无法分享: " + e.getMessage());
        }
    }
}
