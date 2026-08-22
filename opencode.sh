#!/bin/bash
# opencode.sh — Launch OpenCode CLI for the claw-island project
#
# Usage:
#   ./opencode.sh          — interactive TUI
#   ./opencode.sh run ...  — run prompt (non-interactive)
#
# Ensures opencode runs with claw-island's local config and repo context.
# Model: opencode-go/glm-5.3 (high reasoning) · small: opencode-go/deepseek-v4-flash (high reasoning)
# API key: loaded from .env (OPENCODE_GO_API_KEY) via {env:...} in config.
#

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Load .env into environment ───────────────────────────────────────
if [ -f "$DIR/.env" ]; then
  set -a
  source "$DIR/.env"
  set +a
fi

# ── Ensure opencode is on PATH ───────────────────────────────────────
if ! command -v opencode &>/dev/null; then
  export PATH="$HOME/.opencode/bin:$PATH"
fi

# ── Ensure cargo is on PATH (non-interactive shells don't source .bashrc) ──
export PATH="$HOME/.cargo/bin:$PATH"

if ! command -v opencode &>/dev/null; then
  echo "❌ OpenCode not found. Install it:"
  echo "   curl -fsSL https://opencode.ai/install | bash"
  exit 1
fi

cd "$DIR"

# If first arg is "run" or non-interactive commands, run from the project dir
if [ "${1:-}" = "run" ] || [ "${1:-}" = "serve" ] || [ "${1:-}" = "web" ]; then
  exec opencode "$@"
fi

# Default: interactive TUI
echo "🏝️ Claw Island (Android 16 + OriginOS Atomic Island) — OpenCode"
echo "   Config: $DIR/opencode.json"
echo "   Model:  opencode-go/glm-5.3"
echo ""

exec opencode "$DIR"
