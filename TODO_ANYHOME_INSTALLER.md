# AnyHome Installer Feature — TODO

## Goal
Install AnyHome as default launcher via HiBig Zero app, with auto-download from GitHub releases.

## Steps
1. Download latest AnyHome APK from `https://api.github.com/repos/right9code/AnyHome/releases/latest`
2. Pick the non-debug APK (e.g. `AnyHome-v0.0.2-alpha.apk`, 568KB)
3. Handle download failures: retry up to 3 times, resume partial downloads
4. Verify downloaded APK with `pm install` dry-run or checksum
5. Install via `pm install -r` (root)
6. Set AnyHome as default launcher: `cmd package set-home-activity com.right9code.anyhome/.MainActivity`
7. Freeze launcher3: `pm disable-user --user 0 com.android.launcher3`
8. Show progress/status in SYSTEM tab (downloading... installing... done / failed)
9. If connection drops mid-download, resume from byte offset (HttpURLConnection range header)
10. If install fails, open APK via intent for manual install

## Verification
- `pm list packages -d | grep launcher3` → should show frozen
- `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` → only AnyHome
