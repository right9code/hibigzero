# Plan: Add LocalSend + Per-App Uninstall to the SYSTEM Tab APPS Section

## Context
HiBig Zero's **TAB 1 (SYSTEM)** has an `APPS` section rendered by `MainActivity.addAppInstallerCards()`
(~line 1373), which builds one card per entry in `AppInstaller.APPS`. Each card currently shows the
app name, an `INSTALLED v…` / `NOT INSTALLED` status pill, a description, a hidden progress row, an
error label, and a single `INSTALL` / `UPDATE` button.

`AppInstaller` installs apps from GitHub releases. User apps go in via `pm install -r -d`;
**AnyHome is `isSystem=true`**, so it is packaged as a Magisk module (`hzb-anyhome`) and its
`postInstall` freezes the stock launcher: `pm disable-user --user 0 com.android.launcher3`.
AnyHome is therefore the current default HOME handler.

Goal:
1. Add **LocalSend** (`localsend/localsend`) as a new app card.
2. Add an **UNINSTALL** button to every app card.
3. Make AnyHome's uninstall hand the HOME intent back to the stock launcher **before** the Magisk
   module is removed, so the device never boots with no launcher.

## Confirmed decisions
- **Placement:** UNINSTALL lives on the app cards in the SYSTEM tab `APPS` section, next to
  INSTALL/UPDATE. Shown only when the app is actually installed. TAB 2's `OPTIONS ▾` per-package
  dialog is **not** touched.
- **AnyHome uninstall:** re-enable `com.android.launcher3` → set it back as HOME →
  `magisk --remove-module hzb-anyhome` → card shows `NEEDS REBOOT` with a `REBOOT` button
  (module overlay only disappears after reboot).

## Already-applied edits (made before planning started)
These changes are already in `AppInstaller.java` and must not be duplicated:
- `AppDef` gained a `public String[] postUninstall` field plus a fluent
  `setPostUninstall(String...)` returning `this`.
- The AnyHome entry now chains `.setPostUninstall("su -c 'pm enable com.android.launcher3'",
  "su -c 'cmd package set-home-activity com.android.launcher3/com.android.launcher3.Launcher'")`.

## Change 1 — `AppInstaller.java`: add LocalSend
Append to the `APPS` array (after the MiXplorer entry):

```java
new AppDef("localsend", "LocalSend", "localsend/localsend",
    "LocalSend-.*-android-arm64v8\\.apk",
    false, "org.localsend.localsend",
    "Offline cross-platform file sharing"),
```

Verified against the live GitHub API (tag `v1.18.2`): the matching asset is
`LocalSend-1.18.2-android-arm64v8.apk` (~46.5 MB). The `arm64v8` suffix keeps it distinct from the
`arm32v7`, `google-play`, and `x64` APKs, matching the device's `ARCH = "arm64"`.
It is a **user app** (`isSystem=false`), no `postInstall` (LocalSend requests its own runtime
permissions), no `postUninstall`.

## Change 2 — `AppInstaller.java`: uninstall engine
Reuse the existing `InstallCallback` interface (progress/result) — no new callback type.

```java
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
                // 1. Pre-uninstall cleanup — AnyHome: hand HOME back to launcher3 FIRST
                if (app.postUninstall != null) {
                    callback.onProgress("Restoring system state...", 20);
                    for (String cmd : app.postUninstall) ShellUtils.execRoot(cmd);
                }
                // 2. Remove the app itself
                callback.onProgress("Uninstalling " + app.name + "...", 60);
                boolean ok = app.isSystem
                    ? uninstallSystemApp(context, app, callback)
                    : uninstallUserApp(app, callback);
                if (!ok) { callback.onResult(false, "Uninstall failed"); return; }
                callback.onProgress("Done!", 100);
                callback.onResult(true, app.name + " uninstalled" +
                    (app.isSystem ? " — reboot to finish" : ""));
            } catch (Exception e) {
                callback.onResult(false, app.name + " uninstall failed: " + e.getMessage());
            }
        }
    }).start();
}

private static boolean uninstallUserApp(AppDef app, InstallCallback callback) {
    ShellUtils.CommandResult r = ShellUtils.execRoot("pm uninstall " + app.packageName);
    if (!r.isSuccess()) {
        callback.onResult(false, "pm uninstall failed: " + r.stderr);
        return false;
    }
    return true;
}

private static boolean uninstallSystemApp(Context context, AppDef app, InstallCallback callback) {
    String moduleId = "hzb-" + app.id;   // matches installAsSystemApp()'s module id
    callback.onProgress("Removing Magisk module " + moduleId + "...", 70);
    ShellUtils.CommandResult r = ShellUtils.execRoot("magisk --remove-module " + moduleId);
    if (!r.isSuccess()) {
        // Module never existed (e.g. AnyHome installed as a user APK) — fall back
        ShellUtils.CommandResult pm = ShellUtils.execRoot("pm uninstall " + app.packageName);
        return pm.isSuccess();
    }
    return true;
}
```

Key safety property: `postUninstall` runs **before** module removal, so while AnyHome is still the
resolved HOME handler we re-enable launcher3 and repoint HOME at it. If
`set-home-activity com.android.launcher3/com.android.launcher3.Launcher` targets the wrong activity
class on this build, the pending reboot is still safe: once the AnyHome package is gone, Android
drops the stale preferred HOME and auto-selects launcher3 (the sole enabled candidate).

## Change 3 — `MainActivity.java`: UNINSTALL button + wiring
In `addAppCard()` (line ~1379):

1. After the existing `installBtn` setup and `topRow.addView(installBtn)`, add an `uninstallBtn`
   (`"UNINSTALL"`, `styleEinkButton(uninstallBtn, false)` outline style to distinguish from the
   filled INSTALL button), and add it to `topRow`. Set
   `uninstallBtn.setVisibility(installed ? View.VISIBLE : View.GONE);`

2. Add a private class-level helper so install-result and uninstall-result share one state refresh:
   ```java
   private void updateAppCardState(AppInstaller.AppDef app, boolean installed,
           TextView statusPill, Button installBtn, Button uninstallBtn)
   ```
   It sets the pill to `INSTALLED v…`/`NOT INSTALLED`, flips `installBtn` between `UPDATE`/`INSTALL`,
   and shows/hides `uninstallBtn`. Refactor the existing success branch of the install callback to
   call it (keeping the separate `app.isSystem && !nowInstalled` NEEDS-REBOOT path intact).

3. `uninstallBtn` click → confirmation `AlertDialog`. For `isSystem` apps the message adds:
   *"The stock launcher will be restored. A reboot is required to complete removal."* On confirm,
   call a new private `runUninstall(...)` that disables both buttons, shows the existing progress
   row (`"Uninstalling…"`, `0%`), and invokes `AppInstaller.uninstall(...)`.

4. In the uninstall `onResult` (posted to `mainHandler`):
   - **Failure:** re-enable buttons, keep `UNINSTALL` visible, show `"FAILED: …"` in `errorLabel`.
   - **User-app success:** `updateAppCardState(app, false, …)` → pill `NOT INSTALLED`, button
     `INSTALL`, uninstall button hidden.
   - **System-app success:** pill → `NEEDS REBOOT` (outlined), hide `uninstallBtn`, repurpose
     `installBtn` to `REBOOT` with the same confirm-then-`svc power reboot` dialog already used by
     the install NEEDS-REBOOT path (line ~1533).

## Risks / edge cases
- **No-launcher boot** (the scenario the user flagged): fully mitigated by running
  `postUninstall` before `magisk --remove-module`, plus the reboot fallback described above.
- **Idempotency:** if the user uninstalls AnyHome, picks `LATER`, and re-opens the app before
  rebooting, `isInstalled` still returns true (overlay still mounted) so the card shows
  `INSTALLED` again. Re-clicking UNINSTALL runs `magisk --remove-module`, which now fails and falls
  back to `pm uninstall` — harmless, and the NEEDS-REBOOT state is re-shown. Acceptable.
- **`magisk --remove-module` availability:** present in Magisk 24+; the code requires Magisk v26+
  per the README, so the CLI is safe, with `pm uninstall` as a fallback.
- **LocalSend asset name drift:** if a future release renames the arm64 APK, `getDownloadUrl`
  returns null and the card reports `APK not found in release assets` rather than mis-selecting.
- **Download size:** LocalSend arm64 APK is ~46 MB; the existing resume+retry downloader handles it.

## Validation
1. `python3 build.py` — must compile cleanly (Java 8 source via `javac` + `d8`) and produce a signed
   APK in `releases/`. This is the only real compile gate; there is no unit-test harness.
2. Manual on-device checks after install:
   - LocalSend card appears in SYSTEM → APPS; `INSTALL` downloads/installs it; pill flips to
     `INSTALLED v…`; `UNINSTALL` appears; `UNINSTALL` removes it and the pill returns to
     `NOT INSTALLED`.
   - AnyHome `UNINSTALL` → confirm → progress → `NEEDS REBOOT`; after reboot:
     - `pm list packages | grep anyhome` returns nothing,
     - `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
       resolves to `com.android.launcher3/…` (not AnyHome),
     - `pm list packages -d` no longer lists `com.android.launcher3` as frozen.
3. Regression check: the existing INSTALL / UPDATE / NEEDS-REBOOT flow still behaves as before.
