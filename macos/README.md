# Claw Island for macOS

Native SwiftUI menu-bar client for the Claw Island session bridge.

## Features

- Live SSE monitoring with cursor-based event recovery and exponential reconnect.
- Active, recent, and lifecycle-event panes with session detail views.
- OpenCode and Codex source badges, todos, tokens, cost, elapsed time, and staleness.
- Native lifecycle notifications with deduplication and burst summaries.
- Configurable gateway, pause/reconnect controls, diagnostics, preview fixtures, and launch at login.

The default gateway is `http://100.64.0.4:18818`.

## Build and test

```sh
./scripts/self-test.sh
./scripts/package-app.sh
open "dist/Claw Island.app"
```

The app targets macOS 14 or later and has no third-party dependencies.
