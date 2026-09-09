# APK platform beta status

On September 9, 2026, the maintainer reported that most features and functionality had been tested end to end on Even Realities G2 glasses with an R1 ring and a Samsung Galaxy Z Fold7. The platform has a broad feature set, and bugs may remain in functionality or combinations not encountered during that testing.

A beta branch is recommended for wider testing before treating the APK platform as stable. This report describes the maintainer's hands-on experience; it does not claim exhaustive coverage of every feature, device or candidate build. Automated results should identify the exact tested revision separately.

The firmware display-wake fix honors the selected navigation provider's `wakeFocus` preference when waking an unlocked display. Without an override, focus remains on the sidebar. Locked display wake and wake-word-only activation retain sidebar focus; duplicate display-wake events do not steal existing focus. Regression tests cover provider focus, default and duplicate wake behavior, repeated sleep/wake, and locked input blocking.
