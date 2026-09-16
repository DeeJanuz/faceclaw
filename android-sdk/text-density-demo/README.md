# Text Density Lab

This standalone Faceclaw APK renders eight list pages. Each page adds two rows and seven characters per row, reaching 16 rows and about 1,070 visible characters at level 8. Every transition is a 700 ms horizontal page swipe driven by host render credits.

- Click, pointer-click, or scroll down: next density.
- Double-click, back, or scroll up: previous density.
- Long-press: toggle retained copy plus repair.
- The phone activity exposes independent firmware pre-cache and retained-copy toggles plus basic navigation controls.

The renderer always submits a complete Gray8 target. It also registers the benchmark's printable monospace characters as a shared firmware glyph table and attaches their `DrawBatch.glyph` placements to every frame. After a settled frame is acknowledged, the app calls `prefetch` for the complete character set. A compatible host uploads those immutable glyphs to the firmware's 64 KiB texture cache while the display is idle, then sends compact mode-14 strings during the next swipe. Repeated characters reuse the same cached raster. Retained-copy mode independently adds one move hint for the body below the fixed header. Raster pixels remain authoritative when either optimization is unavailable.

Build with Android SDK 35 and JDK 17:

```sh
./gradlew :text-density-demo:assembleDebug
```

The APK is written to `text-density-demo/build/outputs/apk/debug/text-density-demo-debug.apk`.
