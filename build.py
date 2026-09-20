#!/usr/bin/env python3
"""
HiBig Zero - Standalone Release APK Builder
Author: right9code
Target: Bigme HiBreak B6 BW / Color (MediaTek MT6765 Helio P35) | Android 14
Package: com.right9code.hibigzero
"""

import os, sys, re, shutil, hashlib, subprocess, tempfile

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
RELEASES_DIR = os.path.join(ROOT_DIR, "releases")
os.makedirs(RELEASES_DIR, exist_ok=True)

MANIFEST_PATH = os.path.join(ROOT_DIR, "AndroidManifest.xml")


def read_manifest_version():
    """
    The manifest is the single source of truth for the version.

    This used to be hardcoded in three places here - the banner, the APK filename
    and the checksum line - which is the same drift that already bit this project
    once when the UI banner fell out of sync with the real version. Reading it means
    a release bump is one edit, in one file.
    """
    with open(MANIFEST_PATH, "r") as f:
        manifest = f.read()
    m = re.search(r'android:versionName="([^"]+)"', manifest)
    if not m:
        raise SystemExit("build.py: could not read android:versionName from AndroidManifest.xml")
    return m.group(1)


VERSION_NAME = read_manifest_version()
APK_NAME = f"HiBigZero-v{VERSION_NAME}-release.apk"

JAVAC_BIN     = "/usr/bin/javac"
AAPT_BIN      = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/aapt"
D8_BIN        = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/d8"
ZIPALIGN_BIN  = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/zipalign"
APKSIGNER_BIN = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/apksigner"
ANDROID_JAR   = "/home/right9zzz/Android/Sdk/platforms/android-35/android.jar"
# ── Release signing identity ─────────────────────────────────────────────────
# Lives OUTSIDE the repo (this repo is public, so a signing key must never be
# committed). Override with HIBIGZERO_KEYSTORE / HIBIGZERO_KEYSTORE_PROPS.
KEYSTORE_PROPS = os.environ.get(
    "HIBIGZERO_KEYSTORE_PROPS",
    os.path.expanduser("~/.config/hibigzero/keystore.properties"))
KEYSTORE_PATH = os.environ.get(
    "HIBIGZERO_KEYSTORE",
    os.path.expanduser("~/.config/hibigzero/hibigzero-release.jks"))

# Every build must be signed with the SAME certificate, or nobody running an
# older version can install it (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
#   c378f6f7... = current identity (v1.3.4+, rescued out of /tmp on 2026-09-20)
#   2238ad46... = pre-1.3.4 identity, LOST when /tmp was wiped. Installs signed
#                 with it can only be replaced by uninstall + reinstall.
EXPECTED_CERT_SHA256 = "c378f6f758b98f175d3b9d0ff9652fb8e77c3a3299fc7cf500223fc87f791463"


def load_signing_identity():
    path, alias, storepass, keypass = KEYSTORE_PATH, "androiddebugkey", None, None
    if os.path.exists(KEYSTORE_PROPS):
        with open(KEYSTORE_PROPS) as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k, v = k.strip(), v.strip()
                if k == "storeFile":
                    path = os.path.expanduser(v)
                elif k == "keyAlias":
                    alias = v
                elif k == "storePassword":
                    storepass = v
                elif k == "keyPassword":
                    keypass = v
    # Environment wins over the properties file, so a one-off override (or a
    # CI run with a different identity) is possible without editing anything.
    env_path = os.environ.get("HIBIGZERO_KEYSTORE")
    if env_path:
        path = os.path.expanduser(env_path)
    env_alias = os.environ.get("HIBIGZERO_KEYALIAS")
    if env_alias:
        alias = env_alias
    storepass = os.environ.get("HIBIGZERO_STOREPASS", storepass)
    keypass = os.environ.get("HIBIGZERO_KEYPASS", keypass) or storepass
    return path, alias, storepass, keypass


def init_key():
    """Deliberately start a NEW signing identity. Never done automatically."""
    path, alias, storepass, keypass = load_signing_identity()
    if os.path.exists(path):
        print("Refusing to touch an existing keystore: " + path)
        sys.exit(1)
    if not storepass:
        storepass = keypass = "android"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run([
        "keytool", "-genkeypair", "-v",
        "-keystore", path, "-storepass", storepass,
        "-alias", alias, "-keypass", keypass,
        "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
        "-dname", "CN=right9code,O=HiBigZero,C=US"
    ], check=True)
    props = ["storeFile=" + path, "storePassword=" + storepass,
             "keyAlias=" + alias, "keyPassword=" + keypass]
    with open(KEYSTORE_PROPS, "w") as fh:
        fh.write(chr(10).join(props) + chr(10))
    os.chmod(KEYSTORE_PROPS, 0o600)
    print("Created a NEW signing identity at " + path)
    print("Reminder: every existing installation must be uninstalled first.")


def ensure_keystore():
    path, alias, storepass, keypass = load_signing_identity()
    if not os.path.exists(path):
        print("=" * 62)
        print("FATAL: release keystore not found:")
        print("  " + path)
        print()
        print("Refusing to build. Silently generating a new key here changes the")
        print("app's signature and permanently breaks updates for every existing")
        print("installation (INSTALL_FAILED_UPDATE_INCOMPATIBLE).")
        print()
        print("Restore the keystore, set HIBIGZERO_KEYSTORE / HIBIGZERO_KEYSTORE_PROPS,")
        print("or run:  python3 build.py --init-key     (deliberate new identity)")
        print("=" * 62)
        sys.exit(1)
    if not storepass:
        print("FATAL: no keystore password. Expected: " + KEYSTORE_PROPS)
        sys.exit(1)
    print("Signing key: " + path + " (alias " + alias + ")")
    return path, alias, storepass, keypass

def build_release():
    print("=" * 60)
    print(f"HiBig Zero v{VERSION_NAME} Release APK Builder")
    print("Package: com.right9code.hibigzero")
    print("Author:  right9code")
    print("=" * 60)

    identity = ensure_keystore()
    final_apk = os.path.join(RELEASES_DIR, APK_NAME)
    checksum_file = final_apk + ".sha256"

    with tempfile.TemporaryDirectory() as tmpdir:
        classes_dir = os.path.join(tmpdir, "classes")
        os.makedirs(classes_dir, exist_ok=True)

        src_dir = os.path.join(ROOT_DIR, "src")
        res_dir = os.path.join(ROOT_DIR, "res")
        assets_dir = os.path.join(ROOT_DIR, "assets")
        manifest_path = os.path.join(ROOT_DIR, "AndroidManifest.xml")

        # 1. Compile Java sources
        print("Step 1/6: Compiling Java sources...")
        java_files = []
        for root, _, files in os.walk(src_dir):
            for f in files:
                if f.endswith(".java"):
                    java_files.append(os.path.join(root, f))

        subprocess.run([
            JAVAC_BIN, "-source", "8", "-target", "8",
            "-cp", ANDROID_JAR,
            "-d", classes_dir
        ] + java_files, check=True)

        # 2. Convert to Dalvik DEX (d8)
        print("Step 2/6: Converting to Dalvik bytecode (d8)...")
        dex_files = []
        for root, _, files in os.walk(classes_dir):
            for f in files:
                if f.endswith(".class"):
                    dex_files.append(os.path.join(root, f))

        subprocess.run([
            D8_BIN, "--min-api", "28",
            "--output", tmpdir
        ] + dex_files, check=True)

        # 3. Package resources & assets with aapt
        print("Step 3/6: Packaging resources & assets with aapt...")
        unaligned_apk = os.path.join(tmpdir, "app-unaligned.apk")
        aapt_cmd = [
            AAPT_BIN, "package", "-f",
            "-M", manifest_path,
            "-I", ANDROID_JAR,
            "-F", unaligned_apk
        ]
        if os.path.exists(res_dir):
            aapt_cmd.extend(["-S", res_dir])
        if os.path.exists(assets_dir):
            aapt_cmd.extend(["-A", assets_dir])

        subprocess.run(aapt_cmd, check=True)

        # 4. Add classes.dex into APK
        print("Step 4/6: Adding classes.dex to APK...")
        subprocess.run([
            AAPT_BIN, "add", "-k", unaligned_apk,
            os.path.join(tmpdir, "classes.dex")
        ], cwd=tmpdir, check=True)

        # 5. Zipalign
        print("Step 5/6: Aligning APK with zipalign...")
        aligned_apk = os.path.join(tmpdir, "app-aligned.apk")
        subprocess.run([
            ZIPALIGN_BIN, "-f", "-p", "4",
            unaligned_apk, aligned_apk
        ], check=True)

        # 6. Sign APK
        print("Step 6/6: Signing APK with apksigner...")
        ks_path, ks_alias, ks_pass, kp_pass = identity
        subprocess.run([
            APKSIGNER_BIN, "sign",
            "--ks", ks_path,
            "--ks-key-alias", ks_alias,
            "--ks-pass", "pass:" + ks_pass,
            "--key-pass", "pass:" + kp_pass,
            "--out", final_apk,
            aligned_apk
        ], check=True)

        # 6b. Identity drift guard: a changed certificate silently orphans every
        #     existing install, so make it impossible to miss.
        cert_out = subprocess.run(
            [APKSIGNER_BIN, "verify", "--print-certs", final_apk],
            capture_output=True, text=True).stdout
        m = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]+)", cert_out)
        produced = (m.group(1).lower() if m else "unknown")
        if produced != EXPECTED_CERT_SHA256:
            print("!" * 62)
            print("WARNING: THE SIGNING IDENTITY CHANGED")
            print("  expected: " + EXPECTED_CERT_SHA256)
            print("  produced: " + produced)
            print("  Existing installs CANNOT update to this build - they must be")
            print("  uninstalled and reinstalled, and users have to be told.")
            print("!" * 62)
        else:
            print("Signing identity verified: " + produced)

    # Compute SHA256 checksum
    h = hashlib.sha256()
    with open(final_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    sha256_str = h.hexdigest()
    with open(checksum_file, "w") as f:
        f.write(f"{sha256_str}  {APK_NAME}\n")

    size_bytes = os.path.getsize(final_apk)
    print("=" * 60)
    print("BUILD SUCCESSFUL!")
    print(f"  Artifact: {final_apk}")
    print(f"  Size:     {size_bytes:,} bytes")
    print(f"  SHA256:   {sha256_str}")
    print("=" * 60)

if __name__ == "__main__":
    if "--init-key" in sys.argv:
        init_key()
    else:
        build_release()
