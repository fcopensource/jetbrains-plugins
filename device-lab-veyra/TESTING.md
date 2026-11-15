# Testing Device Lab Veyra

## Automated

```bash
./gradlew test
```

- `ParsingTest`: adb output captured from a real Android 16 emulator, finding adb on Windows/macOS/Linux, reading
  `applicationId` and deep links from a sample project.
- `DeviceTest`: drives a running emulator (or the device in `DEVICE_LAB_SERIAL`): theme, font, display size, animations,
  layout bounds, taps, RTL, airplane mode, clean status bar, app language, permissions, restart, deep links, screenshot,
  and Reset putting everything back. It uses only apps built into Android (Settings, Contacts, Dialer) and restores
  the device afterwards. Skipped when no device is connected.

```bash
./gradlew verifyPlugin
```

JetBrains' Plugin Verifier against the IDE versions JetBrains recommends for our since-build (2024.3) and the latest
stable Android Studio (`androidStudioVersion` in `gradle.properties`). It must report **Compatible** with no internal
API usage.

## By hand

1. `./gradlew runIde`, open an Android project, start an emulator.
2. **Device Lab** (right side): the device appears within a few seconds; the App field shows the project's applicationId.
3. Click **Worst case**: the app restarts dark, right-to-left, with the largest font and display size.
4. Click **Reset**: everything goes back.
5. Uncheck a permission, open the app: it asks again or shows its "denied" state.
6. Pick a deep link, **Open**: the status line names the screen that opened and the start time.
7. **Screenshot**: the picture opens in the editor.
8. Stop the emulator: the panel says to start one; start it again: it reconnects by itself.

## Publishing

1. Bump `pluginVersion` in `gradle.properties`, add the notes to `CHANGELOG.md` and `changeNotes` in `build.gradle.kts`.
2. `./gradlew buildPlugin` → `build/distributions/device-lab-veyra-<version>.zip`.
3. First release: upload the zip at <https://plugins.jetbrains.com/plugin/add>. Updates: the plugin's page → **Upload Update**,
   or push a tag `device-lab-veyra-v<version>` with the `JETBRAINS_MARKETPLACE_TOKEN` secret set.
