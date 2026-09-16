# Text Density Lab

This standalone Faceclaw APK renders eight list pages. Each page adds two rows and seven characters per row, reaching 16 rows and about 1,070 visible characters at level 8. Every transition is a 700 ms horizontal page swipe driven by host render credits.

- Click, pointer-click, or scroll down: next density.
- Double-click, back, or scroll up: previous density.
- Long-press: toggle retained copy plus repair.
- The phone activity exposes the same optimization toggle and basic navigation controls.

The renderer always submits a complete Gray8 target. In optimized mode it adds one `retainedCopy` hint for the moving body below the fixed header. The host can therefore compare mode 9 plus residual mode 3 repairs against its ordinary raster plan without changing application correctness.

Build with Android SDK 35 and JDK 17:

```sh
./gradlew :text-density-demo:assembleDebug
```

The APK is written to `text-density-demo/build/outputs/apk/debug/text-density-demo-debug.apk`.
