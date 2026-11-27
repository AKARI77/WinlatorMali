package com.winlator.cmod.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The tool surface handed to the model.
 *
 * Every tool here is read-only by construction: it either inspects files/state or runs a command
 * that has been checked against a strict allowlist. There is deliberately no "write file",
 * "install component" or "launch program" tool, because an autonomous writer that can corrupt a
 * Wine prefix is a much bigger promise than "help me figure out why this crashed".
 */
public final class AiTools {
    public static final String TOOL_CONTAINER_CONFIG = "get_container_config";
    public static final String TOOL_DEVICE_INFO = "get_device_info";
    public static final String TOOL_LIST_LOGS = "list_logs";
    public static final String TOOL_READ_LOG = "read_log";
    public static final String TOOL_PROCESSES = "list_wine_processes";
    public static final String TOOL_RUN_COMMAND = "run_readonly_command";
    public static final String TOOL_PREFIX_PROGRAMS = "list_installed_programs";
    public static final String TOOL_COMPONENTS = "get_wincomponents";

    private AiTools() {}

    public static JSONArray definitions(boolean allowCommands) throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(function(TOOL_CONTAINER_CONFIG,
                "Get the current container configuration: name, screen size, graphics driver, DX wrapper, "
                        + "emulator (box64/fexcore), presets, Windows version, environment variables and enabled components. "
                        + "Call this first when diagnosing a crash.",
                schema(new String[]{}, new JSONObject())));

        tools.put(function(TOOL_DEVICE_INFO,
                "Get Android device information: model, Android version, ABI, CPU cores, total RAM, "
                        + "and the detected GPU renderer/vendor. Useful for GPU driver and memory problems.",
                schema(new String[]{}, new JSONObject())));

        tools.put(function(TOOL_COMPONENTS,
                "Get the list of Windows components (direct3d, directsound, vcrun2010, ...) and whether each is "
                        + "enabled or disabled in this container, plus which ones are available in the image.",
                schema(new String[]{}, new JSONObject())));

        tools.put(function(TOOL_LIST_LOGS,
                "List available log files for this session with their sizes. Returns paths you can pass to read_log.",
                schema(new String[]{}, new JSONObject())));

        JSONObject readLogProps = new JSONObject();
        readLogProps.put("path", stringProp("Log file path exactly as returned by list_logs."));
        readLogProps.put("max_lines", integerProp("How many trailing lines to read. Default 200, max 1000."));
        tools.put(function(TOOL_READ_LOG,
                "Read the tail of a log file. Use this on wine_debug.log, logcat.log, dxvk logs or crash reports "
                        + "to find the actual error. Prefer reading the tail: the end is where failures show up.",
                schema(new String[]{"path"}, readLogProps)));

        tools.put(function(TOOL_PROCESSES,
                "List currently running Wine processes with their PIDs. An empty list means the game exited or "
                        + "never started; a stuck process suggests a hang rather than a crash.",
                schema(new String[]{}, new JSONObject())));

        tools.put(function(TOOL_PREFIX_PROGRAMS,
                "List executables installed inside the Wine prefix (drive_c), so you can tell whether the game "
                        + "and its dependencies (vcrun, dotnet, d3d dlls) are actually present.",
                schema(new String[]{}, new JSONObject())));

        if (allowCommands) {
            JSONObject props = new JSONObject();
            props.put("command", stringProp(
                    "A single read-only shell command, run inside the container root. Only these are allowed: "
                            + "ls, cat, head, tail, grep, find, file, du, df, stat, uname, id, ps, which, "
                            + "ldd, strings, md5sum, sha1sum, readelf, wc, sort, uniq, env, date, getprop, "
                            + "dumpsys (gpu/meminfo only), glxinfo, vulkaninfo, wine --version, wine reg query. "
                            + "Pipes with | are allowed only between allowed commands. No redirects, no &&, no ;, "
                            + "no sudo, no rm/mv/cp/chmod/mkdir/touch/kill."));
            props.put("timeout_seconds", integerProp("Timeout in seconds, default 20, max 60."));
            tools.put(function(TOOL_RUN_COMMAND,
                    "Run one read-only diagnostic shell command inside the container environment (same environment "
                            + "variables as the running Wine session). Use it to inspect files, check libraries with ldd, "
                            + "query the Wine registry, or check GPU/vulkan capability. Writes are rejected.",
                    schema(new String[]{"command"}, props)));
        }
        return tools;
    }

    private static JSONObject function(String name, String description, JSONObject parameters) throws JSONException {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    private static JSONObject schema(String[] required, JSONObject properties) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "object");
        json.put("properties", properties);
        json.put("required", new JSONArray(java.util.Arrays.asList(required)));
        return json;
    }

    private static JSONObject stringProp(String description) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "string");
        json.put("description", description);
        return json;
    }

    private static JSONObject integerProp(String description) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", "integer");
        json.put("description", description);
        return json;
    }
}
