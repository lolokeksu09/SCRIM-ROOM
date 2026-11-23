#!/usr/bin/env bash
# One shell process ensures evidence is saved even when instrumentation fails.
set -uo pipefail
mkdir -p ui-evidence
adb shell wm size
adb shell wm density
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace
status=$?
adb pull /sdcard/Android/data/dev.lolokeksu.scrimroom/files/ui-captures/ ui-evidence/ || true
adb exec-out screencap -p > ui-evidence/final-screen.png || true
adb logcat -d -s AndroidRuntime:E > ui-evidence/crashes.log || true
echo "Instrumentation exit status: $status"
exit "$status"
