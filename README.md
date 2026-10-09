# ragbaz-bridge-android

Tiny debuggable Android bridge for remote-android. Exposes a documented broadcast to send SMS and returns a JSON receipt.

## Build (debug)
- Requires Android SDK (ANDROID_HOME or $HOME/Android/Sdk) and JDK 17
- ./gradlew :app:assembleDebug
- APK: app/build/outputs/apk/debug/app-debug.apk

## Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

## Usage (send SMS)
adb shell am broadcast -a com.ragbaz.bridge.SEND_SMS --es to "+46762868969" --es body "hello" [--ei slot 0]

## Receipt
/data/local/tmp/ragbaz-bridge-last.json (and app external/filesDir/bridge-last.json)

## Indra debug link

The debug application opens a full-duplex JSON-lines listener on
`127.0.0.1:8769` while `MainActivity` exists. Start the activity, use
`python3 -m debug.pair --serial SERIAL` from Indra to provision a per-pair key,
then use `adb forward tcp:8769 tcp:8769`, and run `python3 -m debug` from the Indra
checkout. The protocol is documented in `indra/docs/android-debug-link.md`.
Messages have sender UTC/monotonic timestamps and per-connection sequence
numbers. Android sends unsolicited hello/heartbeat events and timestamped
receipts; `DebugServer.publish()` can send additional events independently.
Queues and frames are bounded. This endpoint does not dispatch SMS commands.

The endpoint requires mutual nonce-based HMAC-SHA256 authentication before
accepting traffic. The key is stored only in the app's private
`files/indra-debug.key`; it is provisioned via adb `run-as`, never via TCP.
All frames have direction-separated session MACs and increasing sequences.
Unpaired/wrong-key peers and forged frames are disconnected. No unauthenticated
fallback is provided. `python3 -m debug --heartbeat` measures latency and clock
offset relative to the host and an NTP reference without changing either clock.
