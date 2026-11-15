<div align="center">

<img src="src/main/resources/META-INF/pluginIcon.svg" width="80" alt="Device Lab Veyra" />

# Device Lab Veyra

**Test your Android app the way your users really use it, in one click.**

A control panel for your phone or emulator, inside Android Studio: dark theme, the largest font, Arabic, right-to-left,
slow animations, airplane mode, permissions and deep links, without digging through Settings or typing `adb` commands.

</div>

## Why

Most layout bugs show up only under conditions developers rarely try: a user with the font at 200 %, a phone in Arabic,
dark theme with a hard-coded white background, a permission someone said no to. Trying each one by hand means
Settings → Display → Font size, Developer options, Languages, back to the app, and then remembering to undo it all.
Device Lab puts every one of them a click away, and **Reset** puts the device back exactly as it was.

## Features

| | |
| --- | --- |
| **Looks** | Light / dark / auto theme · font size up to 200 % (Android 14+) · display size Small to Largest · your app's language (Android 13+), including the pseudolocales `en-XA` and `ar-XB` · right-to-left layout, with the app restarted for you |
| **Debug** | Animations off to 10× slower · layout bounds · show taps · airplane mode · a clean status bar for screenshots (12:00, full battery, full signal, no notifications) |
| **Presets** | **Worst case** (largest font + largest display + RTL + dark at once) · **Screenshot ready** · **Slow motion** · **Reset** |
| **Your app** | Finds its package name in `build.gradle` · grant or revoke each runtime permission · opens the deep links declared in `AndroidManifest.xml` and Navigation graphs, and says which screen opened, cold or warm, and how fast · restart · clear data (asks first) · screenshot straight into the editor |

Works with emulators and real phones (USB or Wi-Fi debugging), Android 8 and newer, in Android Studio and IntelliJ IDEA
2024.3 and newer. It finds `adb` in your project's `local.properties`, `ANDROID_HOME`, the default SDK folder or the
PATH; if your SDK is elsewhere, use **Tools → Device Lab: Set adb Path…**.

## Use it

1. Start an emulator, or connect a phone with USB debugging on.
2. Open **View → Tool Windows → Device Lab** (or **Tools → Open Device Lab**).
3. Check the **App** field shows your app's package name, then click away.

Screenshots are saved to `Pictures/Device Lab Veyra` in your home folder.

## Privacy

Device Lab only talks to `adb` on your computer. No account, no telemetry, nothing sent anywhere.

## Build and test

```bash
./gradlew test          # unit tests, plus end-to-end tests on a running emulator (skipped when none is running)
./gradlew runIde        # try it in a sandbox IDE
./gradlew buildPlugin   # build/distributions/device-lab-veyra-<version>.zip
./gradlew verifyPlugin  # JetBrains Plugin Verifier: IntelliJ IDEA 2024.3+ and the latest Android Studio
```

See [TESTING.md](TESTING.md) for the manual checklist and publishing steps.

Made by [FlightCoders](https://flightcoders.com), the team behind [Veyra Studio](https://veyraeditor.com). MIT licensed.
