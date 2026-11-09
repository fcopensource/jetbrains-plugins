# Testing and shipping Port Manager Veyra

Three ways to try the plugin, from quickest to most thorough, and then how to publish it.

## What you need

- **JDK 17 or newer** to start Gradle. Gradle downloads JDK 21 for the build by itself.
- About **3 GB of free disk** for the first build (Gradle downloads the IntelliJ Platform once).
- A JetBrains IDE for manual testing: any of IntelliJ IDEA, WebStorm, PyCharm, GoLand, PhpStorm, Rider, CLion,
  RubyMine, RustRover or Android Studio, version **2024.3 or newer**.

All commands run in the `port-manager-veyra` folder. On Windows use `gradlew.bat`, on macOS and Linux `./gradlew`.

## 1. Run the automated tests

```bash
./gradlew test
```

- **ModelTest**: the parsers and rules, fed with real output from Windows (`netstat` in English and German, PowerShell,
  `tasklist`), macOS (`lsof`) and Linux (`lsof`, `ss`), plus framework detection and protection rules.
- **SystemTest**: end to end on your real operating system, with nothing mocked. It starts a real server in a child
  process, finds it with the real scanner, stops it with the plugin's Stop code, and checks that the port is free and
  that the IDE's own process and system processes can never be stopped.

The report opens from `build/reports/tests/test/index.html`.

## 2. Try it in a sandbox IDE (recommended)

```bash
./gradlew runIde
```

This starts a separate IntelliJ IDEA with the plugin installed, using its own settings, so your normal IDE is untouched.
Then:

1. Open or create any project.
2. Open **View → Tool Windows → Ports**, or click the **"N ports"** counter in the status bar, or use **Tools → Show Ports in Use**.
3. Start a dev server from the project, for example in the IDE's terminal:
   - `python -m http.server 8000`, or
   - `npx vite`, or `npm run dev`
4. Check that:
   - [ ] a notification says *"… is running on port 8000"* with **Open in browser** and **Stop**
   - [ ] the server appears under **This Project**, with the right framework name, PID and folder
   - [ ] double-clicking it opens `http://localhost:8000/`
   - [ ] **Copy Address** copies the URL
   - [ ] **Stop** asks first, then stops it, and the port disappears
   - [ ] **Free a Port…** with a port in use stops that program; with a free port it says the port is already free
   - [ ] **Show System Ports** reveals system services with a lock icon, and **Stop** is disabled for them
   - [ ] the IDE itself (under **Editors & Tools**) can't be stopped
   - [ ] **Hide This Port** hides a port, **More → Show Hidden Ports** brings it back

## 3. Install it in your own IDE

Build the installable plugin:

```bash
./gradlew buildPlugin
```

This creates `build/distributions/port-manager-veyra-1.0.0.zip`. CI also produces it on every push: open the
**Actions** tab on GitHub, pick the latest run, and download the **port-manager-veyra** artifact.

Then in any JetBrains IDE:

1. **Settings** (Windows/Linux: `Ctrl+Alt+S`, macOS: `⌘,`) → **Plugins**.
2. Click the **⚙ gear** → **Install Plugin from Disk…**
3. Choose the `.zip` (don't unzip it) and click **OK**.
4. Restart the IDE if it asks.

To remove it: **Settings → Plugins → Installed → Port Manager Veyra → Uninstall**.

## 4. Check compatibility with JetBrains' Plugin Verifier

```bash
./gradlew verifyPlugin
```

Checks the plugin against the IDE versions JetBrains recommends (the oldest supported and the newest releases) and
reports any API the plugin uses that's missing or deprecated. CI runs this on every push. It downloads those IDEs, so the
first run takes a while.

## 5. Publish to JetBrains Marketplace

### First release (by hand)

1. Sign in at [plugins.jetbrains.com](https://plugins.jetbrains.com) with a JetBrains Account.
2. Create the vendor profile: avatar → **Add organization** → **FlightCoders**, with the website and contact email.
3. **Upload plugin** (top right): choose the `.zip` from step 3, vendor **FlightCoders**, license **MIT**, category **Tools Integration**,
   tags `ports`, `localhost`, `dev server`, `process`. Add the screenshots from `docs/`.
4. Submit. JetBrains reviews new plugins before they're listed, which usually takes a couple of business days, and emails you.

### Every release after that (automatic)

1. On the Marketplace, open your profile → **My Tokens** → **Generate Token** (name it "GitHub").
2. On GitHub, open the repository → **Settings → Secrets and variables → Actions → New repository secret**:
   name `JETBRAINS_MARKETPLACE_TOKEN`, value the token.
3. Bump `pluginVersion` in `gradle.properties` and add a line to `changeNotes` in `build.gradle.kts`.
4. Push a tag: `git tag port-manager-veyra-v1.0.1 && git push origin port-manager-veyra-v1.0.1`.

CI tests on all three operating systems, verifies, and publishes the new version. JetBrains reviews updates too, usually faster.
