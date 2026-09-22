# Portable Glanceboard app

This directory is a complete Android project. It builds using an SDK Maven artifact, with no Faceclaw source checkout or included Gradle project. Copy it anywhere, set `ANDROID_HOME` and JDK 17 or later, and run:

```sh
./gradlew -PfaceclawSdkRepository=/absolute/path/to/sdk-maven -PfaceclawSdkVersion=YOUR_SDK_VERSION assembleDebug
```

Use an SDK candidate with Glanceboard registry v1 and content v2 support. The development candidates in this workspace are local artifacts, not Maven Central releases. To distribute the SDK to another developer, provide its Maven directory or publish those immutable artifacts to your Maven repository and configure its URL.

Install the APK, approve its service in Faceclaw and approve the host connection, and enable content previews. The contents picker will discover Example tasks and Example status. Choose Example status in both vertically adjacent slots. No host edits, app IDs, or special registry entries are required. The service is passive; no permission to send messages or perform system actions is requested.

See the SDK Glanceboard guide for contract limits, lifecycle, testing, and the JavaScript CLI.
