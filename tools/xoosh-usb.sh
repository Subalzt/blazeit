#!/usr/bin/env bash
# Forwards a local port to Xoosh on the phone over USB. See the .bat for why
# localhost matters: browsers treat it as a secure context even over plain HTTP,
# which enables parallel downloads and the clipboard API without a certificate.
set -euo pipefail
PORT=8787

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install android-platform-tools, or unzip the portable"
  echo "platform-tools next to this script."
  exit 1
fi

adb start-server >/dev/null 2>&1 || true
state=$(adb devices | awk 'NR>1 && NF {print $2; exit}')
if [ "${state:-}" = "unauthorized" ]; then
  echo "Phone connected but not authorised. Unlock it and tap Allow USB debugging."
  exit 1
fi
if [ "${state:-}" != "device" ]; then
  echo "No phone found. Check: data cable (not charge-only), USB debugging on, Allow tapped."
  exit 1
fi

adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null
trap 'adb forward --remove tcp:'"$PORT"' >/dev/null 2>&1 || true' EXIT

echo "Xoosh is at  http://localhost:$PORT"
( command -v xdg-open >/dev/null && xdg-open "http://localhost:$PORT" ) 2>/dev/null \
  || ( command -v open >/dev/null && open "http://localhost:$PORT" ) 2>/dev/null || true

echo "Leave this running. Ctrl+C to disconnect."
while true; do sleep 3600; done
