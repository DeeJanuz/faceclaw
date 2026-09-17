# Faceclaw Diagnostics

An installable, launcher-visible incident recorder for physical-device testing.
It records content-free SDK session failures plus selected host log events for:

- frame latency at 150 ms and 500 ms thresholds;
- frame timeouts and unusual terminal outcomes;
- BLE transport failure, reconnect, late-ACK, and ready events;
- Faceclaw process crashes and ANRs;
- T3 notification preview handoff attempts;
- manually marked incidents with bounded memory and Bluetooth snapshots.

The monitor keeps four rotating 1.5 MB text logs. It excludes rendered pixels,
notification text, prompts, audio, URLs, and Bluetooth addresses. **Mark issue
now** preserves a timestamp and system snapshot. **Export report** writes the
combined history to `Downloads/Faceclaw Diagnostics`.

Build and install:

```sh
./gradlew :diagnostics-demo:assembleDebug
adb install -r diagnostics-demo/build/outputs/apk/debug/diagnostics-demo-debug.apk
adb shell pm grant com.faceclaw.diagnostics android.permission.READ_LOGS
adb shell pm grant com.faceclaw.diagnostics android.permission.DUMP
```

The two development permissions are required for host-wide evidence and can
only be granted over ADB. Without them, the app still records its own SDK
connection and frame-outcome callbacks.
