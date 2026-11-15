<div align="center">

# FlightCoders JetBrains Plugins

**Plugins for IntelliJ IDEA, WebStorm, PyCharm, Android Studio and every JetBrains IDE**, by the team behind
[Veyra Studio](https://veyraeditor.com) and the [Veyra extensions for VS Code](https://marketplace.visualstudio.com/publishers/flightcoders).

</div>

## Plugins

| | Plugin | What it does | Status |
| --- | --- | --- | --- |
| <img src="port-manager-veyra/src/main/resources/META-INF/pluginIcon.svg" width="40" alt="" /> | **[Port Manager Veyra](port-manager-veyra)** | See every port in use, the program behind it and the project it belongs to, and stop it in one click. Recognises 50+ dev servers and databases. | 1.0.0 · [![Build](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/port-manager-veyra.yml/badge.svg)](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/port-manager-veyra.yml) |
| <img src="device-lab-veyra/src/main/resources/META-INF/pluginIcon.svg" width="40" alt="" /> | **[Device Lab Veyra](device-lab-veyra)** | A control panel for your Android phone or emulator in Android Studio: dark theme, 200 % font, Arabic and right-to-left, slow animations, airplane mode, permissions and deep links in one click. The **Worst case** preset finds layout bugs in seconds. | 1.0.0 · [![Build](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/device-lab-veyra.yml/badge.svg)](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/device-lab-veyra.yml) |
| <img src="veyra-theme/src/main/resources/META-INF/pluginIcon.svg" width="40" alt="" /> | **[Veyra Theme](veyra-theme)** | A warm, modern dark and light theme in the FlightCoders colours: coral, amber and violet on deep ink. Themes the whole IDE (with the Islands look on 2025.3+) and every language in the editor. | 1.0.0 · [![Build](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/veyra-theme.yml/badge.svg)](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/veyra-theme.yml) |

More are on the way.

## Repository layout

Each plugin is a self-contained Gradle project in its own folder, with its own README, tests, changelog and CI
workflow in [`.github/workflows`](.github/workflows):

```
port-manager-veyra/     Port Manager Veyra
device-lab-veyra/       Device Lab Veyra (Android Studio)
veyra-theme/            Veyra Theme (dark and light)
.github/workflows/      build, test on Windows/macOS/Linux, verify and publish, one workflow per plugin
```

## Building a plugin

You need JDK 17 or newer; Gradle downloads JDK 21 and the IntelliJ Platform by itself.

```bash
cd port-manager-veyra
./gradlew test          # unit and end-to-end tests
./gradlew runIde        # try it in a sandbox IDE
./gradlew buildPlugin   # build/distributions/*.zip, installable via Settings → Plugins → ⚙ → Install Plugin from Disk…
```

Each plugin's `TESTING.md` has the full checklist and the publishing steps.

## Releasing

Push a tag named `<plugin-folder>-v<version>`, for example `port-manager-veyra-v1.0.1`. CI tests on every OS,
runs JetBrains' Plugin Verifier and publishes to JetBrains Marketplace using the `JETBRAINS_MARKETPLACE_TOKEN`
repository secret.

---

© 2026 [FlightCoders](https://flightcoders.com)
