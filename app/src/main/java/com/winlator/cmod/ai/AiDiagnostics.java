package com.winlator.cmod.ai;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.logs.LogFiles;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Builds the system prompt and the opening context message.
 *
 * The point is that the user should be able to type "游戏打不开，帮我看看" and get a real answer
 * without pasting log excerpts by hand. So the first message already carries the device, the
 * container configuration and the tail of the current session's logs.
 */
public final class AiDiagnostics {

    /** Keep the auto-attached context modest; the model can ask for more via tools. */
    private static final int MAX_LOG_TAIL_LINES = 120;
    private static final int MAX_LOG_TAIL_BYTES = 24_000;

    private AiDiagnostics() {}

    public static String buildSystemPrompt(Context context, boolean commandsAllowed) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are the built-in AI assistant of Winlator Mali, an Android app that runs Windows ")
              .append("games through Wine, Box64/FEXCore, DXVK/VKD3D and an X server. You are embedded in the ")
              .append("game session itself: a floating ball opens this chat, and you can inspect the running ")
              .append("container with the tools you have been given.\n\n");

        prompt.append("## How to work\n")
              .append("1. When the user reports a crash, a black screen, a missing DLL, an error dialog or a ")
              .append("performance problem, investigate before answering. Use get_container_config, ")
              .append("get_device_info and list_logs, then read_log on the most relevant files.\n")
              .append("2. Read the END of logs, not the beginning. Failures are at the tail.\n")
              .append("3. Quote the specific error line you found. \"It crashed\" is useless; ")
              .append("\"err:module:import_dll Library d3dx9_43.dll not found\" is actionable.\n")
              .append("4. Explain the cause in plain language, then give the concrete fix as numbered steps ")
              .append("that a non-technical user can follow by tapping around this app.\n")
              .append("5. If the evidence is inconclusive, say so and ask for the one piece of information ")
              .append("that would settle it, instead of guessing confidently.\n\n");

        prompt.append("## What you cannot do\n")
              .append("You have no ability to modify anything: no writing files, no installing components, ")
              .append("no changing container settings, no launching programs. If the fix requires a change, ")
              .append("describe exactly what the user should change and where, and let them do it. Never ")
              .append("claim you performed an action you cannot perform.\n\n");

        if (commandsAllowed) {
            prompt.append("run_readonly_command exists for extra inspection only (checking a library with ")
                  .append("ldd, listing prefix contents, querying the Wine registry). It rejects anything that ")
                  .append("writes. Do not try to work around its allowlist.\n\n");
        }
        else {
            prompt.append("Command execution is disabled in settings, so only the file and configuration ")
                  .append("inspection tools are available.\n\n");
        }

        prompt.append("## Context you should assume\n")
              .append("Wine prefixes live in the app's imagefs. Games run under Box64 or FEXCore, so x86 ")
              .append("binaries and Windows-only DRM often fail. Mobile GPUs (Adreno/Mali) plus the Turnip ")
              .append("or system driver are the usual cause of graphics crashes; DXVK version mismatches ")
              .append("are the second most common. Low RAM on the device is the usual cause of crashes that ")
              .append("happen only after a few minutes of play.\n\n");

        prompt.append("## Language\n")
              .append("Reply in the same language the user writes in. If they write Chinese, answer in ")
              .append("Simplified Chinese, using the app's own terminology (容器, 环境, 显卡驱动, 组件). ")
              .append("Keep it short: a diagnosis, the fix, and nothing else. No preamble.\n");

        return prompt.toString();
    }

    /** The block appended to the first user message so the model starts with real data. */
    public static String buildContextBlock(Context context, Container container, ImageFs imageFs) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是当前会话的自动采集信息，请据此分析。\n\n");

        sb.append("### 设备\n");
        sb.append(deviceSummary(context)).append("\n\n");

        if (container != null) {
            sb.append("### 容器配置\n");
            sb.append(containerSummary(container)).append("\n\n");
        }

        if (imageFs != null) {
            sb.append("### 运行环境\n");
            sb.append(imageFsSummary(imageFs)).append("\n\n");
        }

        String logs = recentLogExcerpt(context);
        if (!logs.isEmpty()) {
            sb.append("### 最近的日志（尾部）\n");
            sb.append(logs).append("\n");
        }
        else {
            sb.append("### 最近的日志\n没有找到日志文件。如果用户报告闪退，提醒他在设置里打开日志记录后重现一次。\n");
        }

        return sb.toString();
    }

    public static String deviceSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("- Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("- ABI: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("- CPU 核心: ").append(Runtime.getRuntime().availableProcessors()).append('\n');

        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                sb.append("- 内存: 总 ").append(gb(info.totalMem))
                  .append(" GB, 可用 ").append(gb(info.availMem)).append(" GB\n");
                if (info.lowMemory) sb.append("- 系统报告内存紧张\n");
            }
        }
        catch (Exception ignored) {}

        try {
            File data = context.getFilesDir();
            if (data != null) {
                StatFs stat = new StatFs(data.getAbsolutePath());
                long free = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
                sb.append("- 内部存储可用: ").append(gb(free)).append(" GB\n");
            }
        }
        catch (Exception ignored) {}

        String gpu = com.winlator.cmod.core.GPUInformation.getRenderer(null, context);
        if (gpu != null && !gpu.isEmpty()) sb.append("- GPU: ").append(gpu).append('\n');

        return sb.toString().trim();
    }

    public static String containerSummary(Container container) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 名称: ").append(container.getName()).append('\n');
        sb.append("- 屏幕: ").append(container.getScreenSize()).append('\n');
        sb.append("- 显卡驱动: ").append(container.getGraphicsDriver()).append('\n');
        sb.append("- DX 封装: ").append(container.getDXWrapper());
        String dxConfig = container.getDXWrapperConfig();
        if (dxConfig != null && !dxConfig.trim().isEmpty()) sb.append(" (").append(dxConfig.trim()).append(')');
        sb.append('\n');
        sb.append("- 音频驱动: ").append(container.getAudioDriver()).append('\n');
        sb.append("- 模拟器: ").append(container.getEmulator()).append('\n');
        sb.append("- Box64 版本: ").append(container.getBox64Version()).append('\n');
        sb.append("- Box64 preset: ").append(container.getBox64Preset()).append('\n');
        sb.append("- Wine 版本: ").append(container.getWineVersion()).append('\n');
        sb.append("- Windows 组件: ").append(container.getWinComponents()).append('\n');
        sb.append("- LC_ALL: ").append(container.getLC_ALL()).append('\n');
        sb.append("- 内存回收(RamBooster): ").append(container.isRamBoosterEnabled() ? "开" : "关");
        if (container.isRamBoosterEnabled()) {
            sb.append(" (profile=").append(container.getRamBoosterProfile())
              .append(", smart=").append(container.isRamBoosterSmartMode() ? "on" : "off").append(')');
        }
        sb.append('\n');

        String envVars = container.getEnvVars();
        if (envVars != null && !envVars.trim().isEmpty()) {
            sb.append("- 自定义环境变量: ").append(envVars.trim().replace("\n", " ")).append('\n');
        }
        return sb.toString().trim();
    }

    public static String imageFsSummary(ImageFs imageFs) {
        StringBuilder sb = new StringBuilder();
        sb.append("- imagefs 版本: ").append(imageFs.getVersion()).append('\n');
        sb.append("- Wine 路径: ").append(imageFs.getWinePath()).append('\n');
        sb.append("- 前缀: ").append(imageFs.getWineprefix()).append('\n');
        sb.append("- 有效: ").append(imageFs.isValid() ? "是" : "否（环境可能未安装完整）").append('\n');

        File driveC = new File(imageFs.getWineprefix(), "drive_c");
        if (driveC.isDirectory()) {
            File[] children = driveC.listFiles();
            if (children != null) {
                StringBuilder names = new StringBuilder();
                for (File child : children) {
                    if (!child.isDirectory()) continue;
                    if (names.length() > 0) names.append(", ");
                    names.append(child.getName());
                }
                if (names.length() > 0) sb.append("- drive_c 目录: ").append(names).append('\n');
            }
        }
        else {
            sb.append("- drive_c 不存在，Wine 前缀可能尚未初始化\n");
        }
        return sb.toString().trim();
    }

    /** Concatenated tails of the newest logs, capped so the first request stays cheap. */
    public static String recentLogExcerpt(Context context) {
        List<File> files;
        try {
            files = LogFiles.filesIn(LogFiles.appDir(context));
        }
        catch (Exception e) {
            return "";
        }
        if (files.isEmpty()) return "";

        // Newest first, and prefer wine_debug.log because it holds the module/DLL errors.
        files.sort((a, b) -> {
            boolean wineA = LogFiles.WINE_LOG.equals(a.getName());
            boolean wineB = LogFiles.WINE_LOG.equals(b.getName());
            if (wineA != wineB) return wineA ? -1 : 1;
            return Long.compare(b.lastModified(), a.lastModified());
        });

        StringBuilder sb = new StringBuilder();
        int budget = MAX_LOG_TAIL_BYTES;
        int used = 0;
        for (File file : files) {
            if (used >= 2 && budget <= 0) break;
            String tail = tailOf(file, MAX_LOG_TAIL_LINES, budget);
            if (tail == null || tail.isEmpty()) continue;
            budget -= tail.length();
            used++;
            sb.append("\n**").append(file.getName()).append("** (")
              .append(LogFiles.humanBytes(file.length())).append(")\n```\n")
              .append(tail).append("\n```\n");
        }
        return sb.toString().trim();
    }

    /** @return the last {@code maxLines} lines, or null when unreadable. */
    public static String tailOf(File file, int maxLines, int maxBytes) {
        if (file == null || !file.isFile()) return null;
        int limit = Math.min(maxBytes > 0 ? maxBytes : MAX_LOG_TAIL_BYTES, MAX_LOG_TAIL_BYTES);

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - limit);
            raf.seek(start);
            byte[] buffer = new byte[(int) (length - start)];
            raf.readFully(buffer);
            String text = new String(buffer, "UTF-8");
            if (start > 0) {
                // Drop the partial first line.
                int newline = text.indexOf('\n');
                if (newline >= 0) text = text.substring(newline + 1);
            }
            String[] lines = text.split("\n");
            if (lines.length > maxLines) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - maxLines; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
                text = sb.toString();
            }
            // Redact tokens and keys the same way the log manager does before showing them.
            return LogFiles.redact(text).trim();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
