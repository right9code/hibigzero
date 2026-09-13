package com.right9code.hibigzero;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ShellUtils {
    public static final String LOG_PATH = "/data/local/tmp/hibreak.log";

    public static class CommandResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
        public boolean isSuccess() { return exitCode == 0; }
    }

    private static final java.util.List<String> memoryLogs = new java.util.concurrent.CopyOnWriteArrayList<String>();

    public static CommandResult execRoot(String command) {
        return execRoot(command, true);
    }

    public static CommandResult execRoot(String command, boolean log) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        try {
            Process process = new ProcessBuilder("su", "-c", command).start();
            BufferedReader stdReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = stdReader.readLine()) != null) stdout.append(line).append("\n");
            while ((line = errReader.readLine()) != null) stderr.append(line).append("\n");
            exitCode = process.waitFor();
        } catch (Exception e) {
            stderr.append(e.getMessage());
        }
        CommandResult r = new CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim());
        if (log) {
            String preview = command.replace("\n", " ; ");
            if (preview.length() > 90) preview = preview.substring(0, 90) + "...";
            appendLog("$ " + preview + " [exit=" + r.exitCode + "]" + (r.stdout.isEmpty() ? "" : " -> " + r.stdout.substring(0, Math.min(r.stdout.length(), 50))));
        }
        return r;
    }

    public static CommandResult exec(String command) {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            BufferedReader stdReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = stdReader.readLine()) != null) stdout.append(line).append("\n");
            while ((line = errReader.readLine()) != null) stderr.append(line).append("\n");
            exitCode = process.waitFor();
        } catch (Exception e) {
            stderr.append(e.getMessage());
        }
        return new CommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim());
    }

    public static void appendLog(String msg) {
        try {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String entry = ts + " " + msg;
            memoryLogs.add(entry);
            if (memoryLogs.size() > 100) memoryLogs.remove(0);
        } catch (Exception ignored) {}
    }

    public static String readLog(int maxLines) {
        try {
            if (memoryLogs.isEmpty()) return "(log empty)";
            int start = Math.max(0, memoryLogs.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < memoryLogs.size(); i++) {
                sb.append(memoryLogs.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(log error)";
        }
    }
}
