# Per-App & User-Specific Location Obfuscation for Android (LineageOS 22.1)

This repository contains Android OS modifications that implement **per-app** and **user-specific** location obfuscation features. The goal is to extend Android’s location permission model by allowing users to control the precision of location data on a per-application basis, or even provide a **device-wide “fake” location** to all apps. This empowers users to protect their location privacy beyond the standard precise vs. approximate (fine vs. coarse) options in stock Android.

---

## Repository Overview

The codebase in this repository includes **only the newly created or extended source files** needed to introduce these location obfuscation features. It is meant to be applied on top of the **LineageOS 22.1** codebase. The modifications were developed and tested for the **Fairphone 4** device. When these files are integrated into the LineageOS 22.1 source tree and built properly, they enable the custom location obfuscation functionality on the Fairphone 4.

> **Note:** This repository is *not* a full Android ROM source. To use these changes, you should have the LineageOS 22.1 source for Fairphone 4 and incorporate the files from this repo into their respective locations before building.

---

## Building and Flashing (Fairphone 4)

To build a LineageOS 22.1 ROM for the Fairphone 4 with these modifications, follow the official LineageOS guides for setup and compilation. Once you have merged the changes from this repo into the source tree, use the standard build process:

- **Build Instructions for Fairphone 4:** See the official LineageOS documentation **[here](https://wiki.lineageos.org/devices/FP4/build/)** for setting up the build environment and compiling the ROM for FP4.  
- **Installation (Flashing) Instructions:** After a successful build, follow the LineageOS install guide **[here](https://wiki.lineageos.org/devices/FP4/install/)** to flash the custom ROM onto your Fairphone 4.

These guides cover the prerequisites, environment setup, device-specific steps, and commands required to build and install the ROM. Ensure that you follow all steps (such as unlocking the bootloader, installing recovery, etc.) as described in the official documentation.

---

## Key Components and Features

This project introduces two new location permission modes and the supporting architecture in Android’s framework. Below is a high-level summary of the main components added or modified in the codebase, and their roles in the location obfuscation mechanism:

### Settings UI Extensions
The Android **Settings** application is extended to provide new options under **Privacy → Location**. This includes a **device-wide toggle** for enabling a fake location mode and a setting to specify the **spoofing radius** for obfuscation. Additionally, the per-app permission settings (for each app under the Location Permissions section) now include a **“Custom Location”** option. This lets users choose a custom obfuscation mode when granting location permission to an app (beyond just “Allow precise” or “Allow approximate”).

### Runtime Permission Integration
The **PermissionController** (Android’s component for runtime permission prompts and logic) is modified to incorporate the new location modes. When an app requests location access, the permission dialog and flow now handle the additional modes. For example, the user can grant an app **“Custom (Obfuscated) Location”** permission. The PermissionController ensures that the chosen mode is recorded and respected by the system, seamlessly extending the usual coarse/fine permission choices with our new modes.

### Location Service Hook
Core Android framework services responsible for delivering location updates have been updated to i
