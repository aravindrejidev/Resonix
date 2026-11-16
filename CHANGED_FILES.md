# Changed files only

Relative to the `Resonix-main_1_.zip` you uploaded, **cumulative over rounds 5, 6, 7 and 8** (8 = Direct USB output). Copy the contents of the `Resonix-main/` folder in this zip over your project; every file here is a **complete file** (not a patch), so it simply replaces or adds that file. If you already merged an earlier round, files marked with that round are simply overwritten by their newer version.

Files touched in several rounds (README, CHANGES, build.gradle.kts, AppDatabase, Migrations, PlaybackQueueManager, PlaybackService, PlaybackStateBus, NativeAudioEngine, AudioEngine, native-lib, AppSettings, SettingsScreen, PlayerViewModel, MainActivity, the host tests) contain all of it.

## Delete

- `docs/f` (stray 1-byte file)

## Modified

| File | Rounds |
|---|---|
| `CHANGES.md` | 5+6+7+8 |
| `README.md` | 5+6+7+8 |
| `app/build.gradle.kts` | 5+6 |
| `app/src/main/AndroidManifest.xml` | 5+8 |
| `app/src/main/cpp/AudioEngine.cpp` | 6+8 |
| `app/src/main/cpp/AudioEngine.h` | 6+8 |
| `app/src/main/cpp/CMakeLists.txt` | 8 |
| `app/src/main/cpp/RingBuffer.h` | 6 |
| `app/src/main/cpp/native-lib.cpp` | 6+8 |
| `app/src/main/java/com/resonix/player/MainActivity.kt` | 7+8 |
| `app/src/main/java/com/resonix/player/audio/NativeAudioEngine.kt` | 6+8 |
| `app/src/main/java/com/resonix/player/data/AppDatabase.kt` | 5+7 |
| `app/src/main/java/com/resonix/player/playback/EngineSettings.kt` | 8 |
| `app/src/main/java/com/resonix/player/playback/PlaybackController.kt` | 7 |
| `app/src/main/java/com/resonix/player/playback/PlaybackQueueManager.kt` | 6+7 |
| `app/src/main/java/com/resonix/player/playback/PlaybackService.kt` | 6+7+8 |
| `app/src/main/java/com/resonix/player/playback/PlaybackStateBus.kt` | 7+8 |
| `app/src/main/java/com/resonix/player/ui/AppSettings.kt` | 6+8 |
| `app/src/main/java/com/resonix/player/ui/PlayerViewModel.kt` | 7+8 |
| `app/src/main/java/com/resonix/player/ui/components/HomeMenu.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/components/PlayerMenu.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/components/TrackRows.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/neo/NeoIcons.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/HomeScreen.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/NowPlayingScreen.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/SearchScreen.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/SettingsScreen.kt` | 6+8 |

## New

| File | Rounds |
|---|---|
| `.github/workflows/release.yml` | 6 |
| `app/src/main/cpp/UsbAudio.cpp` | 8 |
| `app/src/main/cpp/UsbAudio.h` | 8 |
| `app/src/main/cpp/UsbDirect.cpp` | 8 |
| `app/src/main/cpp/UsbDirect.h` | 8 |
| `app/src/main/cpp/UsbStreamer.cpp` | 8 |
| `app/src/main/cpp/UsbStreamer.h` | 8 |
| `app/src/main/cpp/UsbTransport.h` | 8 |
| `app/src/main/cpp/UsbfsTransport.cpp` | 8 |
| `app/src/main/cpp/UsbfsTransport.h` | 8 |
| `app/src/main/java/com/resonix/player/data/Migrations.kt` | 5+7 |
| `app/src/main/java/com/resonix/player/data/Playlist.kt` | 7 |
| `app/src/main/java/com/resonix/player/data/PlaylistDao.kt` | 7 |
| `app/src/main/java/com/resonix/player/playback/UsbDirectManager.kt` | 8 |
| `app/src/main/java/com/resonix/player/ui/components/NeoDialogs.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/components/TrackActionsSheet.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/PlaylistsScreen.kt` | 7 |
| `app/src/main/java/com/resonix/player/ui/screens/QueueScreen.kt` | 7 |
| `app/src/main/res/xml/backup_rules.xml` | 5 |
| `app/src/main/res/xml/data_extraction_rules.xml` | 5 |
| `gradle/wrapper/gradle-wrapper.jar` | 5 |
| `gradlew` | 5 |
| `gradlew.bat` | 5 |
| `tools/host-tests/.gitignore` | 6+8 |
| `tools/host-tests/FakeDac.h` | 8 |
| `tools/host-tests/fake/oboe/Oboe.h` | 6 |
| `tools/host-tests/make-media.sh` | 6 |
| `tools/host-tests/run.sh` | 6+8 |
| `tools/host-tests/stubs/android/log.h` | 6 |
| `tools/host-tests/test_engine.cpp` | 6+8 |
| `tools/host-tests/test_ring.cpp` | 6 |
| `tools/host-tests/test_usb.cpp` | 8 |

## After merging

1. `chmod +x gradlew` if your tooling dropped the executable bit.
2. Build once (`./gradlew :app:assembleDebug`). The database is **version 4**; Room writes `app/schemas/com.resonix.player.data.AppDatabase/4.json` — commit it.
3. **Direct USB output**: new native sources are listed in `app/src/main/cpp/CMakeLists.txt`. It is off by default; turn it on in Settings → Audio with a USB DAC plugged in. Android asks for permission to use the DAC; the Signal card should then read `Direct USB · <DAC> · <rate> · <bits>-bit …`. If a DAC misbehaves, `adb logcat -s ResonixUsb` shows what the driver did.
4. If an icon fails to resolve (`NeoIcons.Queue` / `Playlists` / `AddToPlaylist`), swap it for another from the same library.
5. If Room reports a deprecation for `fallbackToDestructiveMigrationFrom(1, 2)` / `...OnDowngrade()`, use the overloads with `dropAllTables = true`.
6. Optional, on Linux: `tools/host-tests/run.sh` runs all native tests (engine, gapless, Direct USB against a simulated DAC).
7. For signed releases add the four repository secrets listed at the top of `.github/workflows/release.yml`.

## What was and was not verified here

* Native C++ (ring buffer, engine, gapless, **USB driver**): compiled against the real Oboe 1.11.0 headers and run on a Linux host with the real engine, decoder (FFmpeg), DSP and USB code against a simulated USB DAC: 214 checks (130 engine + 84 USB), also under AddressSanitizer/UBSan and ThreadSanitizer (no warnings), repeated and under CPU load. **No real DAC and no NDK build were involved**; the usbfs layer has never run against hardware.
* The playlist SQL was run in SQLite and compared with what Room expects.
* `PlaybackQueueManager`, `PlaybackStateBus`, `NativeAudioEngine`, `UsbDirectManager`, `Playlist`, `PlaylistDao`: compiled with Kotlin 2.4.20 against stubs for coroutines and Room.
* **Everything Compose**, `PlayerViewModel`, `PlaybackService`, `SettingsScreen`, `AppDatabase`, Gradle and workflow files: written by following the existing code closely and checked for syntax and missing imports only. **Please do a real build; expect a few small compile errors.**
* Nothing was run on a phone.
