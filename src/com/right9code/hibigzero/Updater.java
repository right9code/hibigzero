package com.right9code.hibigzero;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HiBig Zero self-updater.
 * Checks for updates from GitHub releases, downloads the latest APK with progress reporting,
 * and performs root installation via /data/local/tmp with fallback to the system installer.
 */
public class Updater {

    public static final String REPO = "right9code/hibigzero";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public interface UpdateListener {
        void onChecking();
        void onUpToDate(String currentVersion, String latestVersion, Runnable forceInstall);
        void onUpdateAvailable(String currentVersion, String latestVersion, String releaseNotes, Runnable proceed);
        void onProgress(String message, int percent);
        void onCompleted(boolean success, String message);
    }

    public static void check(final Context context, final UpdateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onChecking();
                try {
                    String currentVersion = getCurrentVersion(context);
                    if (currentVersion == null) {
                        if (listener != null) listener.onCompleted(false, "Cannot determine current version");
                        return;
                    }

                    // 1. Fetch release JSON from GitHub API
                    HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "HiBigZero-Updater");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.connect();

                    int code = conn.getResponseCode();
                    if (code == 403) {
                        if (listener != null) listener.onCompleted(false, "GitHub API rate limit reached — try again later");
                        return;
                    }
                    if (code != 200) {
                        if (listener != null) listener.onCompleted(false, "GitHub API returned HTTP " + code);
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();

                    // 2. Parse release metadata
                    JSONObject release = new JSONObject(sb.toString());
                    String tag = release.optString("tag_name", "");
                    final String body = release.optString("body", "");
                    final String remoteVersion = tag.startsWith("v") ? tag.substring(1) : tag;

                    JSONArray assets = release.optJSONArray("assets");
                    String foundUrl = null;
                    long foundSize = 0;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk") && !name.contains("-debug")) {
                                foundUrl = asset.optString("browser_download_url", null);
                                foundSize = asset.optLong("size", 0);
                                break;
                            }
                        }
                    }

                    if (foundUrl == null) {
                        if (listener != null) listener.onCompleted(false, "No release APK asset found on GitHub");
                        return;
                    }

                    final String finalApkUrl = foundUrl;
                    final long finalApkSize = foundSize;
                    final String curVer = currentVersion;

                    int cmp = compareVersions(remoteVersion, curVer);

                    if (cmp > 0) {
                        // Newer version available
                        if (listener != null) {
                            listener.onUpdateAvailable(curVer, remoteVersion, body, new Runnable() {
                                @Override
                                public void run() {
                                    downloadAndInstall(context, finalApkUrl, finalApkSize, remoteVersion, listener);
                                }
                            });
                        }
                    } else {
                        // Already up to date or local is newer (allow reinstall)
                        if (listener != null) {
                            listener.onUpToDate(curVer, remoteVersion, new Runnable() {
                                @Override
                                public void run() {
                                    downloadAndInstall(context, finalApkUrl, finalApkSize, remoteVersion, listener);
                                }
                            });
                        }
                    }

                } catch (Exception e) {
                    if (listener != null) listener.onCompleted(false, "Update check failed: " + e.getMessage());
                }
            }
        }).start();
    }

    public static void downloadAndInstall(final Context context, final String apkUrl,
                                          final long expectedSize, final String version,
                                          final UpdateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File apkFile = null;
                try {
                    if (listener != null) listener.onProgress("Starting download...", 0);
                    apkFile = new File(context.getCacheDir(), "HiBigZero-v" + version + "-update.apk");

                    boolean downloaded = downloadWithResumeAndRetry(apkUrl, apkFile, expectedSize, listener);
                    if (!downloaded) {
                        if (listener != null) listener.onCompleted(false, "Download failed after retries");
                        return;
                    }

                    // Verify APK
                    if (listener != null) listener.onProgress("Verifying APK...", 90);
                    if (!verifyApk(apkFile, expectedSize)) {
                        apkFile.delete();
                        if (listener != null) listener.onCompleted(false, "Corrupted APK download");
                        return;
                    }

                    // Root installation via /data/local/tmp (SELinux-safe)
                    if (listener != null) listener.onProgress("Installing update...", 95);
                    String tmpPath = "/data/local/tmp/hibigzero-update.apk";
                    ShellUtils.execRoot("cp " + apkFile.getAbsolutePath() + " " + tmpPath + " && chmod 644 " + tmpPath);
                    ShellUtils.CommandResult r = ShellUtils.execRoot("pm install -r -d -g " + tmpPath);
                    ShellUtils.execRoot("rm -f " + tmpPath);

                    if (r.isSuccess()) {
                        ShellUtils.appendLog("Updater: installed v" + version + " successfully");
                        if (listener != null) listener.onCompleted(true, "HiBig Zero v" + version + " installed successfully!");
                    } else {
                        ShellUtils.appendLog("Updater: pm install failed (" + r.stderr + "), opening installer");
                        // Fallback: system package installer
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        context.startActivity(intent);
                        if (listener != null) listener.onCompleted(false, "Direct install failed: " + r.stderr + " — opened manual installer");
                    }

                } catch (Exception e) {
                    if (listener != null) listener.onCompleted(false, "Installation error: " + e.getMessage());
                } finally {
                    if (apkFile != null && apkFile.exists()) {
                        apkFile.delete();
                    }
                }
            }
        }).start();
    }

    public static String getCurrentVersion(Context context) {
        try {
            if (context != null) {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            }
        } catch (Exception ignored) {}

        // PackageManager is the authority; "0.0.0" means "unknown" so the
        // updater errs toward offering an update rather than silently skimping.
        return ConfigManager.getAppVersion(context);
    }

    public static int compareVersions(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a.startsWith("v")) a = a.substring(1);
        if (b.startsWith("v")) b = b.substring(1);

        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = 0, vb = 0;
            if (i < pa.length) {
                String num = pa[i].replaceAll("[^0-9].*", "");
                if (!num.isEmpty()) {
                    try { va = Integer.parseInt(num); } catch (Exception ignored) {}
                }
            }
            if (i < pb.length) {
                String num = pb[i].replaceAll("[^0-9].*", "");
                if (!num.isEmpty()) {
                    try { vb = Integer.parseInt(num); } catch (Exception ignored) {}
                }
            }
            if (va != vb) return va - vb;
        }
        return 0;
    }

    private static String resolveRedirectUrl(String urlStr) {
        int maxRedirects = 5;
        String currentUrl = urlStr;
        while (maxRedirects-- > 0) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "HiBigZero-Updater");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location != null && !location.isEmpty()) {
                        currentUrl = location;
                        continue;
                    }
                }
                conn.disconnect();
                return currentUrl;
            } catch (Exception e) {
                return currentUrl;
            }
        }
        return currentUrl;
    }

    private static boolean downloadWithResumeAndRetry(String downloadUrl, File outFile,
                                                     long expectedSize, UpdateListener listener) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            long existingBytes = outFile.exists() ? outFile.length() : 0;
            if (existingBytes > 0 && expectedSize > 0 && existingBytes >= expectedSize) {
                return true;
            }

            HttpURLConnection conn = null;
            try {
                if (listener != null) {
                    listener.onProgress("Downloading... " + formatSize(existingBytes) +
                        (expectedSize > 0 ? " / " + formatSize(expectedSize) : ""),
                        expectedSize > 0 ? (int)(existingBytes * 80 / expectedSize) : 0);
                }

                // Resolve any initial redirect before opening the streaming connection
                String targetUrl = resolveRedirectUrl(downloadUrl);
                conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "HiBigZero-Updater");

                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }

                conn.connect();
                int code = conn.getResponseCode();

                if (code == 416) {
                    conn.disconnect();
                    return true;
                }

                if (code != 200 && code != 206) {
                    conn.disconnect();
                    if (attempt < maxRetries) {
                        Thread.sleep(1000L * attempt);
                        continue;
                    }
                    return false;
                }

                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(outFile, existingBytes > 0);
                byte[] buf = new byte[8192];
                int read;
                long total = existingBytes;

                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                    total += read;
                    if (expectedSize > 0 && listener != null) {
                        int pct = (int)(total * 80 / expectedSize);
                        listener.onProgress("Downloading... " + formatSize(total) +
                            " / " + formatSize(expectedSize), pct);
                    }
                }

                fos.close();
                is.close();
                conn.disconnect();
                return true;

            } catch (Exception e) {
                if (conn != null) conn.disconnect();
                ShellUtils.appendLog("Updater: download attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    private static boolean verifyApk(File apkFile, long expectedSize) {
        if (!apkFile.exists()) return false;
        if (expectedSize > 0 && apkFile.length() != expectedSize) return false;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apkFile, "r");
            byte[] magic = new byte[2];
            raf.readFully(magic);
            raf.close();
            return magic[0] == 'P' && magic[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
