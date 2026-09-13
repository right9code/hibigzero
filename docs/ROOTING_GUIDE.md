# Bigme B6 & HiBreak — Complete Rooting & Unbricking Guide

A comprehensive, step-by-step practical guide for unlocking, backing up, rooting, and unbricking the **Bigme B6** and **Bigme HiBreak** (MT6765 / Helio P35) on Linux (Arch / Ubuntu / Debian / Fedora).

---

## Table of Contents
1. [Device Architecture & Prerequisites](#1-device-architecture--prerequisites)
2. [Workstation Environment Setup](#2-workstation-environment-setup)
3. [Installing MTKClient (With Bigme Auto-Reboot Fix)](#3-installing-mtkclient-with-bigme-auto-reboot-fix)
4. [Linux USB & Udev Permissions](#4-linux-usb--udev-permissions)
5. [Step 1: Enable Developer Options & OEM Unlocking](#step-1-enable-developer-options--oem-unlocking)
6. [Step 2: Bootloader Unlocking](#step-2-bootloader-unlocking)
7. [Step 3: Full Partition Backup (Crucial Safety Net)](#step-3-full-partition-backup-crucial-safety-net)
8. [Step 4: Rooting with Magisk](#step-4-rooting-with-magisk)
   - [4.1 Install Magisk Manager](#41-install-magisk-manager)
   - [4.2 Extract & Push Stock Boot Image](#42-extract--push-stock-boot-image)
   - [4.3 Patch Boot Image via Magisk](#43-patch-boot-image-via-magisk)
   - [4.4 Pull Patched Boot Image](#44-pull-patched-boot-image)
   - [4.5 Flash Patched Boot Image](#45-flash-patched-boot-image)
   - [4.6 Verify Root Access](#46-verify-root-access)
9. [Step 5: Recovery, Restore & Unbricking](#step-5-recovery-restore--unbricking)
10. [Troubleshooting & Hardware Quirks](#troubleshooting--hardware-quirks)

---

## 1. Device Architecture & Prerequisites

### Device Specs
* **SoC:** MediaTek Helio P35 (MT6765)
* **Architecture:** AArch64 (ARM64), 64-bit kernel with 32/64-bit userspace
* **Partition Layout:** A/B slot system-as-root with dynamic partitions (`super`), active boot slot typically `_a`.
* **Hardware Buttons:** Single physical Power button (no physical Volume Up/Down buttons for standard Android recovery combos).
* **Low-Level Interface:** MediaTek BootROM (BROM) exposed over USB (`0e8d:0003`).

### What You Need
1. **Bigme B6 / HiBreak** device with battery charged to at least 50%.
2. **Quality USB-C Cable** (data-capable; avoid charging-only cables).
3. **Linux PC** (Arch Linux, Ubuntu/Debian, or Fedora).

---

## 2. Workstation Environment Setup

### Install Required System Packages

#### On Arch Linux:
```bash
sudo pacman -S android-tools python python-pip python-pipx git libusb
```

#### On Ubuntu / Debian:
```bash
sudo apt update
sudo apt install android-tools-adb android-tools-fastboot python3 python3-pip pipx git libusb-1.0-0-dev
```

#### On Fedora:
```bash
sudo dnf install android-tools python3 python3-pip pipx git libusbx-devel
```

### Verify Command Availability
```bash
which adb fastboot python3 pipx git
```
Ensure all commands resolve successfully.

---

## 3. Installing MTKClient (With Bigme Auto-Reboot Fix)

> [!IMPORTANT]
> **Why use our custom fork instead of upstream?**
> Standard upstream `bkerler/mtkclient` has a critical limitation on MT6765 devices: the hardware watchdog timer is disabled (`enablewdt = 0`) upon DA shutdown. Because the Bigme B6 only has a single physical power button and no hardware recovery keys, the device locks up or enters an unbootable offline charging loop after flashing, forcing manual power button holding.
> 
> Our fork (`right9code/mtkclient`) writes directly to the MT6765 hardware watchdog register (`0x10007014 = 0x1209`) and introduces the `--reboot` and `--fastboot` flags. This enables **100% automated flashing and instant rebooting with zero physical button presses**.

### Installation via `pipx` (Recommended)
`pipx` installs the tool into an isolated virtual environment while making the `mtk` binary available globally across your shell:

```bash
# Ensure pipx environment path is configured
pipx ensurepath

# Install directly from the patched GitHub repository
pipx install git+https://github.com/right9code/mtkclient.git --force
```

### Alternative: Local Clone & Editable Install
```bash
mkdir -p ~/BigmeRoot && cd ~/BigmeRoot
git clone https://github.com/right9code/mtkclient.git
cd mtkclient
pipx install . --force
```

### Verify MTKClient Installation & Custom Flags
Run:
```bash
mtk w -h | grep -E "reboot|fastboot"
```
**Expected output:**
```text
  --reboot              Reboot device to Android after DA operation
  --fastboot            Reboot device to Fastboot mode after DA operation
```
If you see these two flags, your setup has the auto-reboot watchdog fix.

---

## 4. Linux USB & Udev Permissions

MediaTek BROM and Preloader interfaces operate via USB vendor ID `0e8d`. Configure `udev` rules so that non-root users can communicate with the device.

### Add Udev Rules
Run the following commands to create `/etc/udev/rules.d/51-edl.rules`:

```bash
sudo bash -c 'cat << "EOF" > /etc/udev/rules.d/51-edl.rules
# MediaTek BootROM & Preloader
SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", ATTR{idProduct}=="0003", MODE="0666"
SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", ATTR{idProduct}=="2000", MODE="0666"
SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", MODE="0666"
# Android ADB & Fastboot
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666"
EOF'
```

### Reload Rules & Stop ModemManager Interference
ModemManager can grab the MediaTek serial port and disrupt the BROM handshake. Filter or stop it:

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger

# If ModemManager is active, temporarily disable it during flashing
sudo systemctl stop ModemManager.service 2>/dev/null || true
```

---

## 5. Step 1: Enable Developer Options & OEM Unlocking

1. On your Bigme device, navigate to **Settings** > **About Tablet / Device**.
2. Locate **Build number** and tap it **7 to 10 times** until the prompt *"You are now a developer!"* appears.
3. Go back to **Settings** > **System** > **Developer options**.
4. Enable:
   - **OEM unlocking** (Allows bootloader unlock)
   - **USB debugging** (Allows ADB commands)
5. Plug the device into your PC with the USB cable.
6. A prompt will appear on the device screen asking to allow USB debugging: check *"Always allow from this computer"* and tap **Allow**.

### Test ADB Connection
```bash
adb devices
```
**Expected Output:**
```text
List of devices attached
B6BWE0W2GF4A006000461    device
```

---

## 6. Step 2: Bootloader Unlocking

> [!WARNING]
> Unlocking the bootloader erases user data and triggers a factory reset. Ensure any critical documents or files on the device are backed up first.

If your device's bootloader is already unlocked, you can skip to [Step 3](#step-3-full-partition-backup-crucial-safety-net). Otherwise, follow these steps:

### BROM Unlock Method (Via MTKClient)
1. Launch MTKClient unlock listener in a terminal:
   ```bash
   mtk da seccfg unlock
   ```
2. Power off the device:
   ```bash
   adb reboot poweroff
   ```
3. Connect the USB cable while holding the **Power** button for 3–5 seconds until MTKClient detects the BootROM.
4. If prompted to erase user data to complete the unlock:
   ```bash
   mtk e metadata,userdata,md_udc
   ```
5. Power on the device. (If it does not boot immediately, tap the power button periodically until the boot splash displays).

---

## 7. Step 3: Full Partition Backup (Crucial Safety Net)

Before modifying any partition, creating a complete backup of your device's stock partitions is essential for unbricking.

### Automated BROM Catching
Because `adb reboot` briefly cycles through the BootROM initialization phase, you do not need to manually juggle cables or buttons:

1. Create a working backup folder:
   ```bash
   mkdir -p ~/BigmeRoot/backup_stock && cd ~/BigmeRoot
   ```
2. Start the MTKClient partition read listener in the background:
   ```bash
   mtk rl backup_stock &
   ```
3. Trigger a system reboot via ADB:
   ```bash
   adb reboot
   ```
4. **MTKClient will instantly catch the device in BROM mode** during the reboot sequence and begin reading all flash partitions.

*(Note: Reading all partitions including large user storage may take 15–30 minutes. If you only want critical boot and system images, you can read specific partitions using `mtk r boot_a,vbmeta_a,lk_a,recovery_a backup_stock/`).*

### Verify Backup Integrity
Inspect the pulled files:
```bash
cd ~/BigmeRoot/backup_stock

# 1. Verify boot partition exists and has correct Android header
od -A x -t x1z -N 16 boot_a.bin
# Expected output contains: "ANDROID!"

# 2. Verify vbmeta partition
od -A x -t x1z -N 16 vbmeta_a.bin
# Expected output contains: "AVB0"

# 3. Check boot image details
file boot_a.bin
# Expected output: Android bootimg, kernel, page size: 2048...
```

---

## 8. Step 4: Rooting with Magisk

### 4.1 Install Magisk Manager
Download and install the official Magisk Manager APK onto your Bigme device:

```bash
cd ~/BigmeRoot
curl -sL "https://github.com/topjohnwu/Magisk/releases/download/v30.7/Magisk-v30.7.apk" -o magisk.apk
adb install -r magisk.apk
```

### 4.2 Extract & Push Stock Boot Image
Copy the verified `boot_a.bin` to the device's internal storage:

```bash
adb push ~/BigmeRoot/backup_stock/boot_a.bin /sdcard/Download/boot_a.bin
```

### 4.3 Patch Boot Image via Magisk
1. On your Bigme device, open the **Magisk** app.
2. In the top Magisk card, tap **Install**.
3. Under *Method*, tap **Select and Patch a File**.
4. In the file picker, browse to **Downloads** and select `boot_a.bin`.
5. Tap **LET'S GO**. Magisk will unpack the kernel, patch the ramdisk, and repack the image.
6. Look at the output log; it will output:
   ```text
   Output file is written to /storage/emulated/0/Download/magisk_patched-XXXXX_XXXXX.img
   - All done!
   ```

### 4.4 Pull Patched Boot Image
From your Linux PC terminal, retrieve the newly created patched boot image:

```bash
# Locate the generated file name
adb shell ls /sdcard/Download/magisk_patched*

# Pull the image to your workstation as patched_boot_a.img
adb pull $(adb shell ls /sdcard/Download/magisk_patched* | tr -d '\r') ~/BigmeRoot/patched_boot_a.img

# Verify file integrity
file ~/BigmeRoot/patched_boot_a.img
# Expected: Android bootimg...
```

---

### 4.5 Flash Patched Boot Image

Choose either of the two methods below. **Method 1 is recommended** as it is fully automated with zero physical interaction.

#### Method 1: MTKClient (Automated Zero-Button Flashing)
Using our watchdog-enabled fork of MTKClient, you can write the partition and auto-reboot:

```bash
cd ~/BigmeRoot

# 1. Start the flash listener with automatic hardware reboot flag
mtk w boot_a patched_boot_a.img --reboot &

# 2. Trigger reboot via ADB
adb reboot
```
**What happens:**
1. Device reboots.
2. MTKClient catches the BROM interface.
3. MTKClient uploads the DA (Download Agent) and writes `patched_boot_a.img` into `boot_a`.
4. MTKClient writes the MT6765 hardware watchdog reset trigger (`0x10007014 = 0x1209`).
5. **The device automatically boots straight into rooted Android** without touching any physical button!

---

#### Method 2: Fastboot Mode (Alternative)
If you prefer standard Android fastboot:

```bash
# 1. Reboot device into fastboot mode
adb reboot fastboot

# 2. Check active slot (typically 'a')
fastboot getvar current-slot

# 3. Flash the patched boot image to the active boot slot
fastboot flash boot_a ~/BigmeRoot/patched_boot_a.img

# 4. Reboot device
fastboot reboot
```

---

### 4.6 Verify Root Access
Once the device boots up to the desktop:

1. Open **Magisk Manager** on the device. It should now state **Installed: 30.7** (or your version).
2. Test root access in your terminal via ADB:
   ```bash
   adb shell "su -c 'id'"
   ```
   **Expected output:**
   ```text
   uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0
   ```
3. A superuser prompt will appear on your e-ink screen — tap **Grant** (or *Remember permanently*).

**Congratulations! Your Bigme device is now fully rooted.**

---

## 9. Step 5: Recovery, Restore & Unbricking

If the device ever fails to boot, encounters a bootloop, or you want to return to 100% stock firmware:

### Quick Restore to Stock (Fix Bootloops)
Since rooting only modifies the `boot_a` partition, reflashing your stock `boot_a.bin` immediately restores the original state:

```bash
cd ~/BigmeRoot

# Automated BROM restore
mtk w boot_a backup_stock/boot_a.bin --reboot &
adb reboot
```

### Full Factory Unbrick (Cold Device / Black Screen)
If the device is completely powered off or won't boot past the splash screen:

1. Open a terminal and run:
   ```bash
   mtk w boot_a backup_stock/boot_a.bin --reboot
   ```
2. Make sure the USB cable is unplugged.
3. Hold the **Power** button for 10–15 seconds to ensure the device is completely powered off.
4. Insert the USB cable into the PC while holding the **Power** button for 3 seconds.
5. MTKClient will detect the hardware BROM and flash the stock image.

---

## 10. Troubleshooting & Hardware Quirks

| Symptom | Cause | Solution |
| :--- | :--- | :--- |
| `Permission denied` on USB | Missing or unloaded udev rules | Ensure `/etc/udev/rules.d/51-edl.rules` is installed and run `sudo udevadm control --reload-rules && sudo udevadm trigger`. |
| Device freezes in black screen after flashing | Standard upstream `mtkclient` disables the hardware watchdog timer | Use the patched fork (`pipx install git+https://github.com/right9code/mtkclient.git --force`) with the `--reboot` flag. |
| BROM mode not detected on `adb reboot` | Port timing issue | Power off device completely with `adb reboot poweroff`. Start `mtk <command>`, then connect USB while holding Power for 3 seconds. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature mismatch when installing APKs | Run `adb uninstall <package_name>` prior to installing a differently signed build. |
| ModemManager serial port contention | Linux ModemManager attempting AT handshake on BROM port | Run `sudo systemctl stop ModemManager.service`. |

---

## Summary of Critical Files

```text
~/BigmeRoot/
├── backup_stock/
│   ├── boot_a.bin           # Untouched stock boot image (Keep safe!)
│   ├── vbmeta_a.bin         # Verified boot metadata
│   └── lk_a.bin             # Little Kernel bootloader
├── patched_boot_a.img       # Magisk-rooted boot image
└── magisk.apk               # Magisk Manager app
```
