package com.winlator.cmod.ai;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.logs.LogFiles;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the read-only tools the model asks for.
 *
 * Every branch here returns plain text, because that is what the model reads back. Failures are
 * returned as readable messages rather than thrown, so the model can correct itself instead of the
 * whole conversation dying on a missing file.
 */
public final class AiToolExecutor {

    /** Everything the executor needs from the live session. */
    public interface Session {
        Context getContext();
        Container getContainer();
        ImageFs getImageFs();
        /** Environment of the running guest, or an empty set when not launched yet. */
        EnvVars getSessionEnvVars();
        /** Directory commands should run in, usually the imagefs root. */
        File getWorkingDir();
    }

    private final Session session;
    private final AiCommandRunner runner;

    public AiToolExecutor(Session session) {
        this.session = session;
        EnvVars env = session.getSessionEnvVars();
        Map<String, String> envMap = new HashMap<>();
        if (env != null) {
            for (String entry : env.toStringArray()) {
                int split = entry.indexOf('=');
                if (split > 0) envMap.put(entry.substring(0, split), entry.substring(split + 1));
            }
        }
        this.runner = new AiCommandRunner(session.getWorkingDir(), envMap);
    }

    /** @return the tool result text handed back to the model. */
    public String execute(String toolName, String argumentsJson) {
        if (toolName == null) return "ERROR: missing tool name.";
        JSONObject args = parseArguments(argumentsJson);

        try {
            switch (toolName) {
                case AiTools.TOOL_CONTAINER_CONFIG:
                    return containerConfig();
                case AiTools.TOOL_DEVICE_INFO:
                    return AiDiagnostics.deviceSummary(session.getContext());
                case AiTools.TOOL_COMPONENTS:
                    return components();
                case AiTools.TOOL_LIST_LOGS:
                    return listLogs();
                case AiTools.TOOL_READ_LOG:
                    return readLog(args);
                case AiTools.TOOL_PROCESSES:
                    return processes();
                case AiTools.TOOL_PREFIX_PROGRAMS:
                    return prefixPrograms();
                case AiTools.TOOL_RUN_COMMAND:
                    return runCommand(args);
                default:
                    return "ERROR: unknown tool '" + toolName + "'.";
            }
        }
        catch (Exception e) {
            return "ERROR while running " + toolName + ": " + e.getMessage();
        }
    }

    private JSONObject parseArguments(String json) {
        if (json == null || json.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(json);
        }
        catch (Exception e) {
            return new JSONObject();
        }
    }

    private String containerConfig() {
        Container container = session.getContainer();
        StringBuilder sb = new StringBuilder();
        if (container != null) sb.append(AiDiagnostics.containerSummary(container)).append("\n\n");
        else sb.append("(no container bound to this session)\n\n");

        ImageFs imageFs = session.getImageFs();
        if (imageFs != null) sb.append(AiDiagnostics.imageFsSummary(imageFs));
        return sb.toString().trim();
    }

    private String components() {
        Container container = session.getContainer();
        StringBuilder sb = new StringBuilder();
        if (container != null) {
            sb.append("Container wincomponents string:\n").append(container.getWinComponents()).append("\n\n");
            sb.append("Format is key=value where 1 means the builtin component is enabled.\n\n");
        }

        // Report which of the common runtime DLLs actually exist in the prefix, which is what
        // "missing component" complaints usually come down to.
        ImageFs imageFs = session.getImageFs();
        if (imageFs != null) {
            File system32 = new File(imageFs.getWineprefix(), "drive_c/windows/system32");
            if (system32.isDirectory()) {
                String[] interesting = {
                        "d3dx9_43.dll", "d3dx9_36.dll", "d3dcompiler_43.dll", "d3dcompiler_47.dll",
                        "xinput1_3.dll", "xinput9_1_0.dll", "msvcp140.dll", "vcruntime140.dll",
                        "msvcr120.dll", "msvcr100.dll", "mscoree.dll", "dotnet", "dinput8.dll",
                        "dsound.dll", "xaudio2_7.dll", "mfplat.dll", "quartz.dll"
                };
                sb.append("Runtime DLLs present in drive_c/windows/system32:\n");
                for (String name : interesting) {
                    File candidate = new File(system32, name);
                    sb.append("- ").append(name).append(": ").append(candidate.exists() ? "yes" : "no").append('\n');
                }
            }
            else {
                sb.append("drive_c/windows/system32 not found; the prefix may not be initialized.\n");
            }
        }
        return sb.toString().trim();
    }

    private String listLogs() {
        Context context = session.getContext();
        List<File> files = new ArrayList<>();
        files.addAll(LogFiles.filesIn(LogFiles.appDir(context)));
        Container container = session.getContainer();
        if (container != null) {
            File gameDir = LogFiles.gameDir(context, container.getName());
            for (File file : LogFiles.filesIn(gameDir)) {
                if (!files.contains(file)) files.add(file);
            }
        }

        if (files.isEmpty()) {
            return "No log files found. Logging may be disabled, or the game has not been run yet. "
                    + "Log root: " + LogFiles.root(context).getAbsolutePath();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Log root: ").append(LogFiles.root(context).getAbsolutePath()).append("\n\n");
        for (File file : files) {
            sb.append("- ").append(file.getAbsolutePath())
              .append("  (").append(LogFiles.humanBytes(file.length()))
              .append(", modified ").append(LogFiles.timestamp(file.lastModified())).append(")\n");
        }
        return sb.toString().trim();
    }

    private String readLog(JSONObject args) {
        String path = args.optString("path", "");
        if (path.isEmpty()) return "ERROR: 'path' is required. Use list_logs to get a valid path.";

        File file = new File(path);
        // Only allow reading inside the log root, so a crafted path cannot exfiltrate other files.
        File root = LogFiles.root(session.getContext());
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath + File.separator) && !filePath.equals(rootPath)) {
                return "ERROR: only files inside the log root may be read (" + rootPath + ").";
            }
        }
        catch (Exception e) {
            return "ERROR: invalid path.";
        }

        if (!file.isFile()) return "ERROR: no such log file: " + path;

        int maxLines = args.optInt("max_lines", 200);
        if (maxLines < 20) maxLines = 20;
        if (maxLines > 1000) maxLines = 1000;

        String tail = AiDiagnostics.tailOf(file, maxLines, 60_000);
        if (tail == null) return "ERROR: could not read " + path;
        if (tail.isEmpty()) return "(file is empty)";
        return "Tail of " + file.getName() + " (last " + maxLines + " lines):\n" + tail;
    }

    private String processes() {
        List<String> pids;
        try {
            pids = com.winlator.cmod.core.ProcessHelper.listRunningWineProcesses();
        }
        catch (Exception e) {
            return "Could not list Wine processes: " + e.getMessage();
        }
        if (pids == null || pids.isEmpty()) {
            return "No Wine processes are running. The game either exited, crashed, or never started.";
        }
        StringBuilder sb = new StringBuilder("Running Wine-related processes:\n");
        for (String pid : pids) {
            sb.append("- pid ").append(pid).append(": ").append(describeProcess(pid)).append('\n');
        }
        return sb.toString().trim();
    }

    /** Reads /proc/<pid>/cmdline so the model sees which executable is stuck, not just a number. */
    private String describeProcess(String pid) {
        File cmdline = new File("/proc/" + pid + "/cmdline");
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(cmdline.toPath());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(b == 0 ? ' ' : (char) b);
            String text = sb.toString().trim();
            if (text.length() > 300) text = text.substring(0, 300) + "…";
            return text.isEmpty() ? "(no cmdline)" : text;
        }
        catch (Exception e) {
            return "(cmdline unavailable)";
        }
    }

    private String prefixPrograms() {
        ImageFs imageFs = session.getImageFs();
        if (imageFs == null) return "ERROR: no imagefs bound to this session.";

        File driveC = new File(imageFs.getWineprefix(), "drive_c");
        if (!driveC.isDirectory()) return "drive_c does not exist; the Wine prefix is not initialized.";

        StringBuilder sb = new StringBuilder();
        for (String folder : new String[]{"Program Files", "Program Files (x86)", "ProgramData", "windows/system32", "users"}) {
            File dir = new File(driveC, folder);
            if (!dir.isDirectory()) continue;
            File[] children = dir.listFiles();
            if (children == null || children.length == 0) continue;

            List<String> names = new ArrayList<>();
            for (File child : children) {
                // system32 has thousands of dlls; only mention exe/dll counts there.
                if (child.isDirectory()) names.add(child.getName() + "/");
            }
            if (names.isEmpty() && folder.equals("windows/system32")) {
                int dllCount = 0, exeCount = 0;
                for (File child : children) {
                    String lower = child.getName().toLowerCase();
                    if (lower.endsWith(".dll")) dllCount++;
                    else if (lower.endsWith(".exe")) exeCount++;
                }
                sb.append(folder).append(": ").append(dllCount).append(" dll, ")
                  .append(exeCount).append(" exe\n");
                continue;
            }
            if (!names.isEmpty()) {
                sb.append(folder).append(": ").append(String.join(", ", names)).append('\n');
            }
        }

        // Shortcuts tell us what the user actually launches.
        File startMenu = new File(driveC, "ProgramData/Microsoft/Windows/Start Menu/Programs");
        if (startMenu.isDirectory()) {
            List<String> shortcuts = new ArrayList<>();
            collectShortcuts(startMenu, shortcuts, 0);
            if (!shortcuts.isEmpty()) {
                sb.append("\nInstalled shortcuts:\n");
                for (String name : shortcuts) sb.append("- ").append(name).append('\n');
            }
        }

        return sb.length() == 0 ? "The prefix looks empty." : sb.toString().trim();
    }

    private void collectShortcuts(File dir, List<String> out, int depth) {
        if (depth > 3 || out.size() > 60) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectShortcuts(child, out, depth + 1);
            else if (child.getName().toLowerCase().endsWith(".lnk")) out.add(child.getName());
        }
    }

    private String runCommand(JSONObject args) {
        String command = args.optString("command", "");
        if (command.isEmpty()) return "ERROR: 'command' is required.";
        int timeout = args.optInt("timeout_seconds", 20);
        AiCommandRunner.Result result = runner.run(command, timeout);
        return result.describe();
    }
}
