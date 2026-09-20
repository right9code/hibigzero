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
    /** Preview length kept for a logged command and for its first line of output. */
    private static final int LOG_PREVIEW_CHARS = 200;
    /** Rotate the file once it passes this size, keeping the newest LOG_KEEP_BYTES. */
    private static final long LOG_MAX_BYTES = 256L * 1024L;
    private static final long LOG_KEEP_BYTES = 128L * 1024L;
    /** In-memory ring size. The on-disk file is what survives a process restart. */
    private static final int MEMORY_LOG_MAX = 200;


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
            if (preview.length() > LOG_PREVIEW_CHARS) preview = preview.substring(0, LOG_PREVIEW_CHARS) + "...";
            appendLog("$ " + preview + " [exit=" + r.exitCode + "]" + (r.stdout.isEmpty() ? "" : " -> " + r.stdout.substring(0, Math.min(r.stdout.length(), LOG_PREVIEW_CHARS))));
        }
        return r;
    }

    /**
     * Runs a STATE-CHANGING root command, unless DRY_RUN is on - in which case the
     * command is recorded and skipped, and nothing is touched.
     *
     * Call this for anything that alters the device (appops, settings, pm, sysfs
     * writes, reboots). Read-only status probes keep using execRoot() directly, so
     * cards still report real values while dry-run is enabled.
     *
     * The simulated result reports success with the dry-run marker as its stdout, so
     * the "[OK: ...]" labels in the UI describe the plan rather than a false success.
     */
    public static CommandResult execRootAction(String command) {
        return execRootAction(command, true);
    }

    public static CommandResult execRootAction(String command, boolean log) {
        if (ConfigManager.isDryRun()) {
            String preview = command.replace("\n", " ; ");
            if (preview.length() > LOG_PREVIEW_CHARS) preview = preview.substring(0, LOG_PREVIEW_CHARS) + "...";
            if (log) appendLog(ConfigManager.DRY_TAG + preview + " [not applied]");
            return new CommandResult(0, ConfigManager.DRY_TAG + "apply", "");
        }
        return execRoot(command, log);
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

    // ── Log persistence ────────────────────────────────────────────────────
    // Memory alone is not a log: it dies with the process, which is exactly the
    // moment you want to read it. Every entry is also appended to LOG_PATH by a
    // single long-lived writer thread. The writer BATCHES, so a burst of entries
    // (an APPLY ALL RULES pass emits hundreds) costs one root shell instead of one
    // per line - the same root-storm mistake that was already fixed once.
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> fileQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private static final Object fileLock = new Object();
    private static boolean fileWriterStarted = false;
    /** Let a burst finish arriving before spending a root shell on it. */
    private static final long LOG_BATCH_SETTLE_MS = 1000L;

    public static void appendLog(String msg) {
        try {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String entry = ts + " " + msg;
            memoryLogs.add(entry);
            while (memoryLogs.size() > MEMORY_LOG_MAX) memoryLogs.remove(0);
            fileQueue.add(entry);
            synchronized (fileLock) {
                if (!fileWriterStarted) {
                    fileWriterStarted = true;
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() { drainLogQueue(); }
                    }, "hbz-log-writer");
                    t.setDaemon(true);
                    t.start();
                }
                fileLock.notifyAll();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Drains the queue on its own thread. Never runs on the UI thread and never
     * blocks a caller: appendLog() only enqueues.
     */
    private static void drainLogQueue() {
        while (true) {
            synchronized (fileLock) {
                while (fileQueue.isEmpty()) {
                    try {
                        fileLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            try {
                Thread.sleep(LOG_BATCH_SETTLE_MS);
            } catch (InterruptedException e) {
                return;
            }
            drainNow();
        }
    }

    /** Serialises writes so two callers can never interleave a half-built batch. */
    private static final Object writeLock = new Object();

    /** Writes everything currently queued, right now. */
    private static void drainNow() {
        synchronized (writeLock) {
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = fileQueue.poll()) != null) batch.append(line).append('\n');
            if (batch.length() > 0) appendToLogFile(batch.toString());
        }
    }

    /**
     * Flushes queued entries immediately instead of waiting out the batching
     * window. Call this before PendingResult.finish() in a receiver: the process
     * can be reaped the instant finish() returns, and without this the boot and
     * power-event entries - the ones worth reading later - are the ones lost.
     */
    public static void flushLog() {
        try {
            drainNow();
        } catch (Exception ignored) {}
    }

    /**
     * Appends one batch to LOG_PATH as root, rotating first when the file has
     * grown too big. The payload is base64-encoded so command output - which can
     * contain quotes, backslashes, dollars and newlines - cannot escape into the
     * shell. Written 0600: this file records package names and root commands and
     * has no business being world-writable in /data/local/tmp.
     */
    private static void appendToLogFile(String payload) {
        try {
            String b64 = android.util.Base64.encodeToString(
                    payload.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String cmd =
                "F=" + LOG_PATH + "; " +
                "s=$(stat -c %s $F 2>/dev/null || echo 0); " +
                "if [ \"$s\" -gt " + LOG_MAX_BYTES + " ]; then " +
                    "tail -c " + LOG_KEEP_BYTES + " $F > $F.rot 2>/dev/null && mv $F.rot $F; " +
                "fi; " +
                "echo '" + b64 + "' | base64 -d >> $F 2>/dev/null; " +
                "chmod 600 $F 2>/dev/null";
            execRoot(cmd, false);
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

    /**
     * Tail of the on-disk log. This is what shows history that outlived a process
     * restart - including the boot-time rules that ran before the UI existed.
     * Costs one root shell, so call it off the UI thread and only on demand.
     */
    public static String readLogFileTail(int maxLines) {
        try {
            if (maxLines <= 0) return "(log empty)";
            CommandResult r = execRoot("tail -n " + maxLines + " " + LOG_PATH + " 2>/dev/null", false);
            String out = r.stdout;
            return out.isEmpty() ? "(log file empty)" : out;
        } catch (Exception e) {
            return "(log file unreadable)";
        }
    }

    /**
     * The on-disk tail merged with anything still in memory but not yet flushed
     * (the writer batches, so the last second of entries may not be on disk yet).
     * The file is append-only and fed from these same lines, so the memory list is
     * normally a suffix of it - everything after the last flushed line is new.
     */
    public static String readLogMerged(int maxLines) {
        try {
            String tail = readLogFileTail(maxLines);
            if (tail.startsWith("(log file") || tail.startsWith("(log empty)")) {
                String mem = readLog(maxLines);
                return mem;
            }
            if (memoryLogs.isEmpty()) return tail;

            String[] tailLines = tail.split("\n");
            String lastFlushed = tailLines[tailLines.length - 1].trim();

            int from = -1;
            for (int i = memoryLogs.size() - 1; i >= 0; i--) {
                if (memoryLogs.get(i).equals(lastFlushed)) { from = i; break; }
            }

            StringBuilder sb = new StringBuilder(tail);
            if (from >= 0) {
                for (int i = from + 1; i < memoryLogs.size(); i++) {
                    sb.append("\n").append(memoryLogs.get(i));
                }
            } else {
                // The flushed line has already rotated out, so match by content to
                // stay duplicate-free without dropping the newest entries.
                java.util.Set<String> have = new java.util.HashSet<String>();
                for (String l : tailLines) have.add(l.trim());
                for (int i = 0; i < memoryLogs.size(); i++) {
                    if (!have.contains(memoryLogs.get(i))) {
                        sb.append("\n").append(memoryLogs.get(i));
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return readLog(maxLines);
        }
    }
}
