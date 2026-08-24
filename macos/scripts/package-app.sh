#!/bin/zsh
set -euo pipefail

project_dir="${0:A:h:h}"
configuration="${1:-release}"
output_dir="${2:-$project_dir/dist}"
app_dir="$output_dir/Claw Island.app"

cd "$project_dir"
swift build -c "$configuration"
binary_path="$(swift build -c "$configuration" --show-bin-path)/ClawIslandMac"

mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources"
cp "$binary_path" "$app_dir/Contents/MacOS/ClawIslandMac"
cp "$project_dir/Info.plist" "$app_dir/Contents/Info.plist"
codesign --force --deep --sign - "$app_dir"
echo "$app_dir"
