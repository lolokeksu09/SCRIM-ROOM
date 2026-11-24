#!/usr/bin/env bash
set -uo pipefail
mkdir -p ui-evidence
tmp="$(mktemp -d)"
pid=""
cleanup() {
  if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT
adb shell wm size
adb shell wm density
# Isolated synthetic account data and a fresh, loopback-only TLS endpoint per runner.
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -keyout "$tmp/key.pem" -out "$tmp/cert.pem" -subj '/CN=10.0.2.2' \
  -addext 'subjectAltName=IP:10.0.2.2' -addext 'basicConstraints=critical,CA:TRUE' \
  > "$tmp/openssl.log" 2>&1 || exit 1
export SCRIM_DB_PATH="$tmp/pilot.db"
export SCRIM_ADMIN_KEY='test-only-ephemeral-scrim-admin-key-for-local-ci'
python -m uvicorn server.app:app --host 127.0.0.1 --port 8443 \
  --ssl-keyfile "$tmp/key.pem" --ssl-certfile "$tmp/cert.pem" > ui-evidence/test-api.log 2>&1 &
pid=$!
ready=0
for i in $(seq 1 30); do
  if curl --silent --fail --cacert "$tmp/cert.pem" --resolve '10.0.2.2:8443:127.0.0.1' --noproxy '*' https://10.0.2.2:8443/healthz >/dev/null; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then echo 'Isolated HTTPS API did not start'; exit 1; fi
cert="$(openssl x509 -in "$tmp/cert.pem" -outform DER | base64 -w0)"
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace \
  "-Pandroid.testInstrumentationRunnerArguments.scrimTestCert=$cert"
status=$?
adb pull /sdcard/Pictures/ScrimRoomTests/ ui-evidence/ || true
adb exec-out screencap -p > ui-evidence/final-screen.png || true
adb logcat -d -s AndroidRuntime:E > ui-evidence/crashes.log || true
echo "Instrumentation exit status: $status"
exit "$status"
