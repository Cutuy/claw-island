#!/bin/zsh
set -euo pipefail

project_dir="${0:A:h:h}"
test_binary="$(mktemp -d)/claw-island-self-test"
swiftc \
  "$project_dir/Sources/ClawIslandMac/Models.swift" \
  "$project_dir/Sources/ClawIslandMac/NotificationManager.swift" \
  "$project_dir/Sources/ClawIslandMac/SSEParser.swift" \
  "$project_dir/scripts/SelfTest.swift" \
  -o "$test_binary"
"$test_binary"
