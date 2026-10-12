# ragbaz-bridge-android

Tiny debuggable Android bridge for remote-android. Exposes a documented broadcast to send SMS and returns a JSON receipt.

## Build (debug)
- Requires Android SDK (`ANDROID_HOME` or `$HOME/Android/Sdk`), Gradle 8.7 and
  JDK 17 or 21. This `gradlew` delegates to the installed Gradle; JDK 25 is not
  compatible with that Gradle version. Java/Kotlin bytecode targets remain 17.
- ./gradlew :app:assembleDebug
- APK: app/build/outputs/apk/debug/app-debug.apk

## Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

## Call-history read permission

The bridge declares `android.permission.READ_CALL_LOG`. For a sideloaded debug
build, explicitly grant it after installation (choose a serial with `adb devices`):

```sh
adb -s SERIAL shell pm grant com.ragbaz.bridge android.permission.READ_CALL_LOG
```

This is a restricted permission on recent Android versions: the installer must
allowlist it; device policy may still deny the grant. Declaring/granting it does
not itself add a bridge call-history API or make the app the default dialer.
The permission does not allow placing calls or modifying the call log.

On devices that permit shell access, list call history directly:

```sh
adb -s SERIAL shell 'content query --uri content://call_log/calls --projection number:date:duration:type --sort "date DESC"'
```

`date` is Unix milliseconds and `duration` is seconds; common types are incoming
(`1`), outgoing (`2`), and missed (`3`). Keep call-history output private.

## OSHI and dependency inventories

OSHI uses the published Java-8-compatible `com.github.oshi:oshi-core:6.9.3`
artifact. JNA and JNA-platform are pinned to the same `5.18.1` version; the
desktop JNA JAR is excluded in favor of the Android AAR so the APK contains
`libjnidispatch.so`. Duplicate JNA license resources are merged, not discarded.
`OshiReceiver` uses OSHI's actual `oshi.*` API. Unsupported native metrics report
an error receipt rather than taking down the bridge. Load average is not reported
as CPU utilization. No new OSHI broadcast endpoint is enabled by this change.

Generate the APK and **resolved transitive** CycloneDX 1.6 dependency inventories:

```sh
JAVA_HOME=/path/to/jdk-17-or-21 ANDROID_HOME=/path/to/Android/Sdk ./gradlew sbom
python3 scripts/verify_sbom.py
# For release, instead: ./gradlew sbom -PsbomVariant=release
```

- `sbom/build.cdx.json`: Gradle buildscript/plugin graph, compile classpath,
  Kotlin compiler/plugin and annotation-processor configurations, Gradle,
  executing JDK, Android SDK platform and build-tools versions.
- `sbom/runtime.cdx.json`: selected variant's runtime classpath and Android
  minimum/target API requirements. Build-only plugins are not labeled runtime.

Both reports include dependency edges, selected Maven versions/artifact SHA-256
properties, generation timestamp/variant, and the SHA-256 of the assembled APK.
They are inventories, not license-compliance or vulnerability-clearance claims.
Regenerate them whenever dependencies, tooling or the APK change. Variant-specific
reports overwrite these two files; archive them alongside the matching APK when
publishing. Local paths, keys, contacts and call history are not recorded.

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
