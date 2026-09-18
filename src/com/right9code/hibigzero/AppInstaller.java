package com.right9code.hibigzero;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Core engine for downloading, installing, and updating apps from GitHub releases.
 * Handles resume, retries, Magisk module creation for system apps, APK verification.
 */
public class AppInstaller {

    // ── App definitions ──────────────────────────────────────────────────
    public static final String ARCH = "arm64"; // HiBreak B6 is arm64

    public static class AppDef {
        public final String id;
        public final String name;
        public final String repo;          // owner/repo
        public final String assetPattern;  // regex for APK filename
        public final boolean isSystem;     // true = install as Magisk system app
        public final String packageName;
        public final String description;
        public final String[] postInstall; // extra commands after install
        public String[] postUninstall;     // commands run before uninstall (e.g. restore launcher)

        public AppDef(String id, String name, String repo, String assetPattern,
                      boolean isSystem, String packageName, String description,
                      String... postInstall) {
            this.id = id;
            this.name = name;
            this.repo = repo;
            this.assetPattern = assetPattern;
            this.isSystem = isSystem;
            this.packageName = packageName;
            this.description = description;
            this.postInstall = postInstall;
        }

        /** Fluent setter for cleanup commands run before uninstall (restores default launcher, etc.) */
        public AppDef setPostUninstall(String... cmds) {
            this.postUninstall = cmds;
            return this;
        }
    }

    public static final AppDef[] APPS = {
        new AppDef("anyhome", "AnyHome", "right9code/AnyHome",
            // Match the release APK but skip the -debug variant (negative lookbehind)
            "AnyHome-.*(?<!debug)\\.apk",
            true, "com.right9code.anyhome",
            "Minimal e-ink launcher",
            "pm disable-user --user 0 com.android.launcher3")
            // Uninstall must hand the HOME intent back to the stock launcher
            // BEFORE the Magisk module is removed, or the device boots with no launcher.
            .setPostUninstall(
                "pm enable com.android.launcher3",
                "cmd package set-home-activity com.android.launcher3/com.android.launcher3.Launcher"),
        new AppDef("koreader", "KOReader", "koreader/koreader",
            "koreader-android-arm64-.*\\.apk",
            false, "org.koreader.launcher",
            "Full-featured e-book reader",
            "pm grant org.koreader.launcher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; true",
            "pm grant org.koreader.launcher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; true",
            "cmd appops set org.koreader.launcher MANAGE_EXTERNAL_STORAGE allow"),
        new AppDef("einkbro", "E-Ink Bro", "plateaukao/einkbro",
            "app-arm64-v8a-release\\.apk",
            false, "info.plateaukao.einkbro",
            "E-ink optimized web browser"),
        new AppDef("obsidian", "Obsidian", "obsidianmd/obsidian-releases",
            "Obsidian.*\\.apk",
            false, "md.obsidian",
            "Knowledge base & notes",
            "cmd appops set md.obsidian MANAGE_EXTERNAL_STORAGE allow"),
        new AppDef("mixplorer", "MiXplorer", "driftywinds/mixplorer-releases",
            "MiXplorer_.*\\.apk",
            false, "com.mixplorer",
            "Powerful file manager with root access",
            "cmd appops set com.mixplorer MANAGE_EXTERNAL_STORAGE allow"),
        new AppDef("localsend", "LocalSend", "localsend/localsend",
            "LocalSend-.*-android-arm64v8\\.apk",
            false, "org.localsend.localsend_app",
            "Offline cross-platform file sharing"),
    };

    // ── API response cache (5 min TTL) ──────────────────────────────────
    private static final ConcurrentHashMap<String, CacheEntry> apiCache =
        new ConcurrentHashMap<String, CacheEntry>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private static class CacheEntry {
        final String json;
        final long timestamp;
        CacheEntry(String json) {
            this.json = json;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    // ── Callback interface ───────────────────────────────────────────────
    public interface InstallCallback {
        /** Called from background thread — update UI */
        void onProgress(String message, int percent);
        void onResult(boolean success, String message);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Check if app is installed */
    public static boolean isInstalled(String packageName) {
        ShellUtils.CommandResult r = ShellUtils.execRoot(
            "pm path " + packageName + " 2>/dev/null", false);
        return r.isSuccess() && r.stdout.contains("package:");
    }

    /** Get installed version, or null */
    public static String getInstalledVersion(String packageName) {
        ShellUtils.CommandResult r = ShellUtils.execRoot(
            "dumpsys package " + packageName + " 2>/dev/null | grep versionName | head -1", false);
        if (r.stdout.contains("=")) {
            return r.stdout.split("=")[1].trim();
        }
        return null;
    }

    /** Get latest version from GitHub API (cached 5 min) */
    public static String getLatestVersion(String repo) throws Exception {
        CacheEntry cached = apiCache.get(repo);
        if (cached != null && !cached.isExpired()) {
            return extractTag(cached.json);
        }
        String json = fetchGitHubAPI("https://api.github.com/repos/" + repo + "/releases/latest");
        apiCache.put(repo, new CacheEntry(json));
        return extractTag(json);
    }

    /** Get download URL for matching asset */
    public static String getDownloadUrl(String repo, String assetPattern) throws Exception {
        CacheEntry cached = apiCache.get(repo);
        String json;
        if (cached != null && !cached.isExpired()) {
            json = cached.json;
        } else {
            json = fetchGitHubAPI("https://api.github.com/repos/" + repo + "/releases/latest");
            apiCache.put(repo, new CacheEntry(json));
        }
        return findAssetUrl(json, assetPattern);
    }

    /** Get asset file size from cached API response */
    public static long getAssetSize(String repo, String assetPattern) throws Exception {
        CacheEntry cached = apiCache.get(repo);
        String json;
        if (cached != null && !cached.isExpired()) {
            json = cached.json;
        } else {
            json = fetchGitHubAPI("https://api.github.com/repos/" + repo + "/releases/latest");
            apiCache.put(repo, new CacheEntry(json));
        }
        return findAssetSize(json, assetPattern);
    }

    /**
     * Download and install an app. Runs on background thread.
     * - Fetches latest version from GitHub API
     * - Downloads APK with resume + retry
     * - Verifies APK before install
     * - For system apps: creates Magisk module and installs via magisk
     * - For user apps: installs via pm install
     * - Runs post-install commands (permissions, etc.)
     */
    public static void install(final Context context, final AppDef app,
                               final InstallCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File apkFile = null;
                try {
                    // 1. Get latest version
                    callback.onProgress("Checking " + app.name + " releases...", 0);
                    String latestVersion = getLatestVersion(app.repo);
                    if (latestVersion == null) {
                        callback.onResult(false, "Could not parse release version");
                        return;
                    }

                    // 1b. Skip if already on latest version.
                    //     getInstalledVersion() reads dumpsys, which still reports a
                    //     version for a system-overlay package that was removed for
                    //     user 0 (pm path empty). Gate on a real install, otherwise the
                    //     installer wrongly short-circuits with "already up to date".
                    String installedVersion = isInstalled(app.packageName)
                        ? getInstalledVersion(app.packageName) : null;
                    if (installedVersion != null && compareVersions(latestVersion, installedVersion) <= 0) {
                        callback.onResult(true, app.name + " is already up to date (v" + installedVersion + ")");
                        return;
                    }

                    // 2. Get download URL
                    String downloadUrl = getDownloadUrl(app.repo, app.assetPattern);
                    if (downloadUrl == null) {
                        callback.onResult(false, "APK not found in release assets");
                        return;
                    }

                    // 3. Get expected size
                    long expectedSize = getAssetSize(app.repo, app.assetPattern);

                    // 4. Download with resume + retry
                    apkFile = new File(context.getCacheDir(), app.id + "-v" + latestVersion + ".apk");
                    boolean downloaded = downloadWithRetry(downloadUrl, apkFile, expectedSize, callback);
                    if (!downloaded) {
                        callback.onResult(false, "Download failed after retries");
                        return;
                    }

                    // 5. Verify APK
                    callback.onProgress("Verifying APK...", 90);
                    if (!verifyApk(apkFile, expectedSize)) {
                        apkFile.delete();
                        callback.onResult(false, "APK verification failed — corrupted download");
                        return;
                    }

                    // 6. Install
                    callback.onProgress("Installing " + app.name + "...", 95);
                    boolean installed;
                    if (app.isSystem) {
                        installed = installAsSystemApp(context, app, apkFile, latestVersion, callback);
                    } else {
                        installed = installAsUserApp(app, apkFile, callback);
                    }

                    if (!installed) {
                        callback.onResult(false, "Installation failed");
                        return;
                    }

                    // 7. Post-install commands
                    if (app.postInstall != null) {
                        for (String cmd : app.postInstall) {
                            callback.onProgress("Running post-install setup...", 98);
                            ShellUtils.execRoot(cmd);
                        }
                    }

                    callback.onProgress("Done!", 100);
                    callback.onResult(true, app.name + " v" + latestVersion + " installed");

                } catch (Exception e) {
                    callback.onResult(false, app.name + " failed: " + e.getMessage());
                } finally {
                    if (apkFile != null && apkFile.exists()) apkFile.delete();
                }
            }
        }).start();
    }

    /**
     * Uninstall an app. Runs on background thread.
     * - Runs pre-uninstall cleanup first (AnyHome: restores the stock launcher as HOME)
     * - For system apps: removes the Magisk module (activates after reboot)
     * - For user apps: pm uninstall
     */
    public static void uninstall(final Context context, final AppDef app,
                                 final InstallCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isInstalled(app.packageName)) {
                        callback.onResult(true, app.name + " is not installed");
                        return;
                    }

                    // 1. Pre-uninstall cleanup. For AnyHome this hands the HOME intent
                    //    back to launcher3 BEFORE the module is removed.
                    if (app.postUninstall != null) {
                        callback.onProgress("Restoring system state...", 20);
                        for (String cmd : app.postUninstall) {
                            ShellUtils.execRoot(cmd);
                        }
                    }

                    // 2. Remove the app. Detect the actual install type at runtime:
                    //    a Magisk /system overlay needs module removal (reboot to finish),
                    //    a user app is removed with pm uninstall.
                    boolean asSystem = isInstalledAsSystem(app.packageName);
                    callback.onProgress("Uninstalling " + app.name + "...", 60);
                    boolean uninstalled;
                    if (asSystem) {
                        uninstalled = uninstallSystemApp(context, app, callback);
                    } else {
                        uninstalled = uninstallUserApp(app, callback);
                    }

                    if (!uninstalled) {
                        callback.onResult(false, "Uninstall failed");
                        return;
                    }

                    callback.onProgress("Done!", 100);
                    callback.onResult(true, app.name + " uninstalled" +
                        (asSystem ? " — reboot to finish" : ""));

                } catch (Exception e) {
                    callback.onResult(false, app.name + " uninstall failed: " + e.getMessage());
                }
            }
        }).start();
    }

    // ── Internal methods ─────────────────────────────────────────────────

    private static String fetchGitHubAPI(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.connect();

        int code = conn.getResponseCode();
        if (code == 403) {
            throw new Exception("GitHub API rate limited — try again in a few minutes");
        }
        if (code != 200) {
            throw new Exception("GitHub API error: HTTP " + code);
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    private static String extractTag(String json) throws Exception {
        JSONObject release = new JSONObject(json);
        String tag = release.optString("tag_name", null);
        if (tag == null) return null;
        // Strip leading 'v'
        if (tag.startsWith("v")) tag = tag.substring(1);
        return tag;
    }

    private static String findAssetUrl(String json, String pattern) throws Exception {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        JSONObject release = new JSONObject(json);
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            if (p.matcher(name).matches()) {
                return asset.getString("browser_download_url");
            }
        }
        return null;
    }

    private static long findAssetSize(String json, String pattern) throws Exception {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        JSONObject release = new JSONObject(json);
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            if (p.matcher(name).matches()) {
                return asset.optLong("size", 0);
            }
        }
        return 0;
    }

    /**
     * Download with resume support and 3 retries with exponential backoff.
     * If connection drops at X bytes, resumes from X on next attempt.
     */
    private static boolean downloadWithRetry(String downloadUrl, File outFile,
                                             long expectedSize, InstallCallback callback) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            long existingBytes = outFile.exists() ? outFile.length() : 0;
            if (existingBytes > 0 && expectedSize > 0 && existingBytes >= expectedSize) {
                return true; // already complete
            }
            try {
                callback.onProgress("Downloading... " + formatSize(existingBytes) +
                    (expectedSize > 0 ? " / " + formatSize(expectedSize) : ""),
                    expectedSize > 0 ? (int)(existingBytes * 80 / expectedSize) : 0);

                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                // Resume from where we left off
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }

                conn.connect();

                int code = conn.getResponseCode();
                if (code == 416) {
                    // Range not satisfiable — file already complete
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
                    if (expectedSize > 0) {
                        int pct = (int)(total * 80 / expectedSize);
                        callback.onProgress("Downloading... " + formatSize(total) +
                            " / " + formatSize(expectedSize), pct);
                    }
                }
                fos.close();
                is.close();
                conn.disconnect();

                ShellUtils.appendLog("AppInstaller: downloaded " + outFile.getName() +
                    " (" + total + " bytes)");
                return true;

            } catch (Exception e) {
                ShellUtils.appendLog("AppInstaller: download attempt " + attempt +
                    " failed: " + e.getMessage());
                if (attempt < maxRetries) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    /** Verify downloaded APK: check size matches expected */
    private static boolean verifyApk(File apkFile, long expectedSize) {
        if (!apkFile.exists()) return false;
        if (expectedSize > 0 && apkFile.length() != expectedSize) return false;
        // Basic check: APK is a zip, should start with PK magic bytes
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

    /** Install as user app via pm install -r -d -g through /data/local/tmp staging */
    private static boolean installAsUserApp(AppDef app, File apkFile, InstallCallback callback) {
        callback.onProgress("Installing " + app.name + " (user app)...", 96);
        String staged = "/data/local/tmp/" + app.id + ".apk";
        try {
            // Stage to /data/local/tmp (SELinux-safe for pm install)
            ShellUtils.CommandResult cp = ShellUtils.execRoot(
                "cp " + apkFile.getAbsolutePath() + " " + staged + " && chmod 644 " + staged);
            if (!cp.isSuccess()) {
                callback.onResult(false, "Failed to stage APK: " + cp.stderr);
                return false;
            }
            // Install with -r (replace), -d (allow downgrade), -g (grant permissions)
            ShellUtils.CommandResult r = ShellUtils.execRoot(
                "pm install -r -d -g " + staged);
            if (!r.isSuccess()) {
                callback.onResult(false, "pm install failed: " + r.stderr);
                return false;
            }
            return true;
        } finally {
            // Clean up staged file
            ShellUtils.execRoot("rm -f " + staged);
        }
    }

    /** Install as system app via Magisk module */
    private static boolean installAsSystemApp(Context context, AppDef app, File apkFile,
                                              String version, InstallCallback callback) {
        try {
            // 1. Create module directory structure
            File moduleDir = new File(context.getCacheDir(), "hzb_module_" + app.id);
            File systemDir = new File(moduleDir, "system/app/" + app.name.replace(" ", ""));
            systemDir.mkdirs();

            // 2. Copy APK into module
            File destApk = new File(systemDir, app.name.replace(" ", "") + ".apk");
            copyFile(apkFile, destApk);

            // 3. Create module.prop
            String moduleId = "hzb-" + app.id;
            String propContent = "id=" + moduleId + "\n" +
                "name=HiBig " + app.name + "\n" +
                "version=v" + version + "\n" +
                "versionCode=" + System.currentTimeMillis() + "\n" +
                "author=right9code\n" +
                "description=HiBig Zero " + app.name + " system app\n";
            writeFile(new File(moduleDir, "module.prop"), propContent);

            // 4. Create customize.sh
            String customScript = "#!/sbin/sh\n" +
                "# Set permissions for " + app.name + "\n" +
                "chmod 755 /data/adb/modules/" + moduleId + "/system/app/" +
                app.name.replace(" ", "") + "\n" +
                "chmod 644 /data/adb/modules/" + moduleId + "/system/app/" +
                app.name.replace(" ", "") + "/" + app.name.replace(" ", "") + ".apk\n";
            writeFile(new File(moduleDir, "customize.sh"), customScript);

            // 5. Create module zip
            File moduleZip = new File(context.getCacheDir(), moduleId + ".zip");
            zipDirectory(moduleDir, moduleZip);

            callback.onProgress("Installing " + app.name + " as system app...", 96);

            // 6. Push zip and install via Magisk
            String zipPath = moduleZip.getAbsolutePath();
            ShellUtils.execRoot("rm -rf " + moduleDir.getAbsolutePath());

            // Remove old module if exists
            ShellUtils.execRoot("rm -rf /data/adb/modules/" + moduleId);

            // Also remove any pre-existing module that already provides this system
            // app (e.g. one created manually or by an earlier build, named "anyhome"
            // instead of "hzb-anyhome"). Otherwise two modules would overlay the same
            // /system path after the reboot.
            for (String existing : findProvidingModules(app)) {
                if (!existing.equals(moduleId)) {
                    ShellUtils.execRoot("rm -rf /data/adb/modules/" + existing);
                    ShellUtils.execRoot("rm -rf /data/adb/modules_update/" + existing);
                }
            }

            // Push to device
            ShellUtils.CommandResult pushResult = ShellUtils.execRoot(
                "cp " + zipPath + " /sdcard/Download/" + moduleId + ".zip");
            if (!pushResult.isSuccess()) {
                callback.onResult(false, "Failed to push module zip");
                return false;
            }

            // Install module
            ShellUtils.CommandResult magiskResult = ShellUtils.execRoot(
                "magisk --install-module /sdcard/Download/" + moduleId + ".zip");
            ShellUtils.execRoot("rm -f /sdcard/Download/" + moduleId + ".zip");

            if (!magiskResult.isSuccess()) {
                callback.onResult(false, "Magisk module install failed: " + magiskResult.stderr);
                return false;
            }

            // Ensure the package is installed for user 0. A prior uninstall leaves a
            // "not installed for user 0" record that survives the module swap and the
            // reboot — the overlay would then mount but stay unusable, and for a
            // launcher that means no HOME candidate (Android falls back to FallbackHome).
            // pm install-existing is the inverse of `pm uninstall --user 0` and is a
            // harmless no-op when the package was never removed.
            ShellUtils.execRoot("pm install-existing " + app.packageName + " 2>/dev/null");

            callback.onProgress("System app installed — reboot required to activate", 99);
            ShellUtils.appendLog("AppInstaller: " + app.name + " Magisk module installed");
            return true;

        } catch (Exception e) {
            callback.onResult(false, "System install failed: " + e.getMessage());
            return false;
        }
    }

    /** Uninstall a user app via pm uninstall */
    private static boolean uninstallUserApp(AppDef app, InstallCallback callback) {
        callback.onProgress("Uninstalling " + app.name + " (user app)...", 70);
        ShellUtils.CommandResult r = ShellUtils.execRoot(
            "pm uninstall " + app.packageName);
        if (!r.isSuccess()) {
            callback.onResult(false, "pm uninstall failed: " + r.stderr);
            return false;
        }
        return true;
    }

    /**
     * Remove a system-app Magisk module overlay. Takes effect after reboot.
     * Our own modules are named hzb-&lt;id&gt;, but the app may have been installed by
     * an earlier build or manually under a different module id (e.g. "anyhome"),
     * so the modules actually providing the overlay are discovered and removed.
     */
    private static boolean uninstallSystemApp(Context context, AppDef app,
                                              InstallCallback callback) {
        java.util.Set<String> moduleIds = findProvidingModules(app);
        if (moduleIds.isEmpty()) moduleIds.add("hzb-" + app.id); // fall back to our own naming

        boolean removed = false;
        for (String moduleId : moduleIds) {
            callback.onProgress("Removing Magisk module " + moduleId + "...", 70);
            // This Magisk build only exposes `--remove-modules` (all modules), so remove
            // the module dirs directly — that is what actually stops the overlay from
            // re-mounting on the next boot. Matches what installAsSystemApp() does.
            ShellUtils.CommandResult rm = ShellUtils.execRoot(
                "rm -rf /data/adb/modules/" + moduleId +
                " /data/adb/modules_update/" + moduleId);
            if (rm.isSuccess()) removed = true;
        }

        // Drop it from user 0 immediately (the mounted overlay lingers until reboot;
        // removing the module dirs above keeps it gone after the reboot).
        ShellUtils.CommandResult pm = ShellUtils.execRoot(
            "pm uninstall --user 0 " + app.packageName);
        if (!removed && !pm.isSuccess()) {
            callback.onResult(false, "System app removal failed");
            return false;
        }

        callback.onProgress("System app removed — reboot required to finish", 90);
        ShellUtils.appendLog("AppInstaller: " + app.name + " module(s) removed");
        return true;
    }

    /** True if the package is installed from /system/ (a Magisk overlay or ROM app). */
    public static boolean isInstalledAsSystem(String packageName) {
        ShellUtils.CommandResult r = ShellUtils.execRoot("pm path " + packageName);
        return r.isSuccess() && r.stdout.contains("/system/");
    }

    /**
     * Find all Magisk module ids that provide this app's system overlay.
     * pm path prints e.g. "package:/system/app/AnyHome/AnyHome.apk"; the module
     * overlay mirrors that tree at /data/adb/modules/&lt;id&gt;/system/app/AnyHome.
     */
    private static java.util.Set<String> findProvidingModules(AppDef app) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        // 1. Follow the live system path (works while the package is installed)
        ShellUtils.CommandResult r = ShellUtils.execRoot("pm path " + app.packageName);
        if (r.isSuccess()) {
            for (String line : r.stdout.split("\n")) {
                int sysIdx = line.indexOf("/system/");
                if (sysIdx < 0) continue;
                String sysPath = line.substring(sysIdx);
                int lastSlash = sysPath.lastIndexOf('/');
                if (lastSlash <= 0) continue;
                collectModuleIds(ids, "/data/adb/modules/*" + sysPath.substring(0, lastSlash));
            }
        }
        // 2. Fallback: scan by the expected overlay dir name. This catches a module
        //    that lingers after the package was removed for the user (pm path is
        //    empty then), so a re-install won't create a duplicate overlay.
        if (ids.isEmpty()) {
            collectModuleIds(ids,
                "/data/adb/modules/*/system/app/" + app.name.replace(" ", ""));
        }
        return ids;
    }

    /** Parse module ids out of `ls -d /data/adb/modules/<id>/...` output. */
    private static void collectModuleIds(java.util.Set<String> ids, String glob) {
        ShellUtils.CommandResult ls = ShellUtils.execRoot("ls -d " + glob);
        for (String mLine : ls.stdout.split("\n")) {
            mLine = mLine.trim();
            String marker = "/data/adb/modules/";
            int start = mLine.indexOf(marker);
            if (start < 0) continue;
            String rest = mLine.substring(start + marker.length());
            int slash = rest.indexOf('/');
            if (slash > 0) ids.add(rest.substring(0, slash));
        }
    }

    // ── File utilities ───────────────────────────────────────────────────

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int read;
        while ((read = fis.read(buf)) != -1) fos.write(buf, 0, read);
        fos.close();
        fis.close();
    }

    private static void writeFile(File file, String content) throws Exception {
        java.io.FileWriter fw = new java.io.FileWriter(file);
        fw.write(content);
        fw.close();
    }

    private static void zipDirectory(File sourceDir, File zipFile) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
        zipRecursive(sourceDir, sourceDir, zos);
        zos.close();
    }

    private static void zipRecursive(File baseDir, File current, ZipOutputStream zos) throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File file : files) {
            String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
            if (file.isDirectory()) {
                zipRecursive(baseDir, file, zos);
            } else {
                zos.putNextEntry(new ZipEntry(relativePath));
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] buf = new byte[8192];
                int read;
                while ((read = fis.read(buf)) != -1) zos.write(buf, 0, read);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Compare semantic version strings. Returns >0 if a>b, <0 if a<b, 0 if equal. */
    private static int compareVersions(String a, String b) {
        // Strip leading 'v' if present
        if (a.startsWith("v")) a = a.substring(1);
        if (b.startsWith("v")) b = b.substring(1);
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = 0, vb = 0;
            try { va = Integer.parseInt(pa[i].replaceAll("[^0-9].*", "")); } catch (Exception ignored) {}
            try { vb = Integer.parseInt(pb[i].replaceAll("[^0-9].*", "")); } catch (Exception ignored) {}
            if (va != vb) return va - vb;
        }
        return 0;
    }
}
