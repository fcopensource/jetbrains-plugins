<div align="center">

<img src="src/main/resources/META-INF/pluginIcon.svg" alt="Port Manager Veyra" width="96" />

# Port Manager Veyra

**"Port 3000 is already in use"? Not any more.**

See every port in use, the program behind it and the project it belongs to, and stop it in one click,
right inside IntelliJ IDEA, WebStorm, PyCharm and every other JetBrains IDE.

[![Build](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/port-manager-veyra.yml/badge.svg)](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/port-manager-veyra.yml)
![Platforms](https://img.shields.io/badge/Windows%20%7C%20macOS%20%7C%20Linux-supported-3574f0)
![IDEs](https://img.shields.io/badge/JetBrains%20IDEs-2024.3%2B-ff7ac6)
![License](https://img.shields.io/badge/license-MIT-22c55e)

[Features](#features) · [Install](#install) · [How it works](#how-it-works) · [Testing](TESTING.md)

</div>

---

## The problem

You start your dev server and get `EADDRINUSE: address already in use :::3000`. Somewhere an old Vite, Django or
Spring Boot process is still holding the port. So you open a terminal, run `netstat -ano | findstr 3000` or
`lsof -i :3000`, copy a PID, and kill it, every time.

**Port Manager Veyra makes that one click.**

## Features

### 🔌 A live Ports tool window

Every listening TCP port on your computer, grouped so the ones you care about come first:

| Group | What's in it |
| --- | --- |
| **This Project** | Servers started from the project you have open |
| **Apps & Dev Servers** | Everything else you started: other projects, scripts, databases |
| **Editors & Tools** | IDEs, editors and tools like Docker |
| **System & Other Users** | Hidden by default; shown with a lock and never stoppable |

Each row shows the **port**, the **framework** it recognised, the **program and PID**, whether it's reachable from
**this computer only** or the network, and the **folder** it runs from. The list refreshes by itself every few seconds.

### 🧠 Knows what's running

Recognises **50+ dev servers, runtimes and databases** from the program and its command line:
Vite, Next.js, Nuxt, Astro, SvelteKit, Remix, Angular, Create React App, Gatsby, Storybook, Expo, webpack, Parcel,
Live Server, Django, Flask, Uvicorn/FastAPI, Rails, Laravel, PHP's built-in server, Hugo, Jupyter, Spring Boot,
Quarkus, Ktor, Tomcat, .NET, PostgreSQL, MySQL/MariaDB, MongoDB, Redis, Docker, Deno, Bun, Node.js, Python,
Java, Ruby and Go.

### ⚡ One-click actions

- **Open in Browser**: double-click any port, or use the toolbar
- **Copy Address**: `http://localhost:5173/` on the clipboard
- **Stop**: asks first, tells you which other ports the program also frees, then stops it
- **Reveal Folder**: open the folder the program runs from
- **Free a Port…**: type `3000`, and whatever holds it is stopped
- **Hide This Port**: for ports you never want to see

### 🔔 "Your server started"

When a dev server starts from your project's folder, a notification tells you its port, with **Open in browser** and
**Stop** buttons. (Turn it off under **⋯ More**.)

### 📊 A status-bar counter

How many ports your apps are using, always visible. Hover for the list; click to open the Ports window.

## Safe by design

- **System services, other users' programs and JetBrains IDEs themselves are protected.** They're shown with a lock
  and can never be stopped, so you can't close your own IDE or break Windows by accident.
- **Every stop asks first.**
- On macOS and Linux, programs are asked to close (`SIGTERM`) before anything harsher. On Windows, the program and the
  processes it started are ended together, so no orphaned children keep the port.
- **Nothing leaves your computer.** No account, no telemetry, no network calls: the plugin only asks your operating
  system which programs are listening.

## Install

**From JetBrains Marketplace** (coming soon): **Settings → Plugins → Marketplace**, search for **Port Manager Veyra**.

**From a build:** download `port-manager-veyra-*.zip` from the latest [Actions run](https://github.com/fcopensource/jetbrains-plugins/actions/workflows/port-manager-veyra.yml),
then **Settings → Plugins → ⚙ → Install Plugin from Disk…**

Open it from **View → Tool Windows → Ports**, **Tools → Show Ports in Use**, or the status bar.

**Works in** IntelliJ IDEA, WebStorm, PyCharm, GoLand, PhpStorm, Rider, CLion, RubyMine, RustRover, DataGrip and
Android Studio, version **2024.3 or newer**, on **Windows, macOS and Linux**.

## How it works

| | Listening ports | Program details |
| --- | --- | --- |
| **Windows** | `netstat -ano` (IPv4 and IPv6, any Windows language) | PowerShell `Win32_Process` (name, path, command line), falling back to `tasklist` |
| **macOS** | `lsof -iTCP -sTCP:LISTEN` | `ps` for the command line, `lsof` for the working folder |
| **Linux** | `lsof`, falling back to `ss -ltnp` | `ps` for the command line, `/proc/<pid>/cwd` for the folder |

Every command runs without a shell and with a timeout. Process details are cached per PID, so after the first scan a
refresh costs only the fast socket listing.

```
src/main/kotlin/com/flightcoders/veyra/ports/
  Model.kt          parsers, framework rules, protection, snapshots and grouping (pure, unit-tested)
  Scanner.kt        runs the OS commands and stops processes
  PortsService.kt   one background scanner shared by every project window
  ui/               the Ports tool window, actions, status-bar widget and notifications
```

## Testing

- **ModelTest**: every parser and rule, with real command output from Windows (English and German `netstat`),
  macOS and Linux.
- **SystemTest**: end to end on the real operating system, with nothing mocked: starts a real server, finds it with the
  real scanner, stops it, and checks the port is free and that protected processes can't be stopped.
- **CI** runs both on **Windows, macOS and Linux** on every push, then builds the plugin and checks it with JetBrains'
  **Plugin Verifier**.

See **[TESTING.md](TESTING.md)** to run the tests, try the plugin in a sandbox IDE, install it in your own IDE, and publish it.

## Also by FlightCoders

- **[Veyra Port Manager for VS Code](https://marketplace.visualstudio.com/items?itemName=flightcoders.veyra-port-manager)**: the same tool for VS Code
- **[Veyra Studio](https://veyraeditor.com)**: the lightweight VS Code alternative

---

MIT License · © 2026 [FlightCoders](https://flightcoders.com)
