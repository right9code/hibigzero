#!/usr/bin/env python3
"""
HiBig Zero - Standalone Release APK Builder v1.3.2
Author: right9code
Target: Bigme HiBreak B6 BW / Color (MediaTek MT6765 Helio P35) | Android 14
Package: com.right9code.hibigzero
"""

import os, sys, shutil, hashlib, subprocess, tempfile

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
RELEASES_DIR = os.path.join(ROOT_DIR, "releases")
os.makedirs(RELEASES_DIR, exist_ok=True)

JAVAC_BIN     = "/usr/bin/javac"
AAPT_BIN      = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/aapt"
D8_BIN        = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/d8"
ZIPALIGN_BIN  = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/zipalign"
APKSIGNER_BIN = "/home/right9zzz/Android/Sdk/build-tools/35.0.0/apksigner"
ANDROID_JAR   = "/home/right9zzz/Android/Sdk/platforms/android-35/android.jar"
DEBUG_KEYSTORE= "/tmp/debug.keystore"

def ensure_keystore():
    if not os.path.exists(DEBUG_KEYSTORE):
        subprocess.run([
            "keytool", "-genkey", "-v",
            "-keystore", DEBUG_KEYSTORE, "-storepass", "android",
            "-alias", "androiddebugkey", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=right9code,O=HiBigZero,C=US"
        ], check=True)

def build_release():
    print("=" * 60)
    print("HiBig Zero v1.3.2 Release APK Builder")
    print("Package: com.right9code.hibigzero")
    print("Author:  right9code")
    print("=" * 60)

    ensure_keystore()
    final_apk = os.path.join(RELEASES_DIR, "HiBigZero-v1.3.2-release.apk")
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
        subprocess.run([
            APKSIGNER_BIN, "sign",
            "--ks", DEBUG_KEYSTORE,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--out", final_apk,
            aligned_apk
        ], check=True)

    # Compute SHA256 checksum
    h = hashlib.sha256()
    with open(final_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    sha256_str = h.hexdigest()
    with open(checksum_file, "w") as f:
        f.write(f"{sha256_str}  HiBigZero-v1.3.2-release.apk\n")

    size_bytes = os.path.getsize(final_apk)
    print("=" * 60)
    print("BUILD SUCCESSFUL!")
    print(f"  Artifact: {final_apk}")
    print(f"  Size:     {size_bytes:,} bytes")
    print(f"  SHA256:   {sha256_str}")
    print("=" * 60)

if __name__ == "__main__":
    build_release()
