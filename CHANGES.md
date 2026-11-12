# Resonix – playback / settings fix pass

## Bugs fixed
| Symptom | Cause | Fix |
|---|---|---|
| Mini player cover did not change with the song | `rememberAlbumArt` used `produceState`, which keeps its *old* value when the key changes, so it never loaded the new cover | State is now created per art URI (`AlbumArt.kt`) |
| Seek bar did nothing, Play/Pause/Next/Prev dead, phone media player empty | Every command went UI → Media3 `MediaController` → `MediaSession` → custom `SimpleBasePlayer` → engine; any break in that chain silently dropped the command and no notification appeared | Media3 removed. `PlaybackService` is now a plain service driven by direct calls (`PlaybackController`), with a framework `MediaSession` + its own media notification |
| Seek landed ~0.1 s early and the bar snapped back | Backward keyframe seek; position kept showing the old value until the decoder caught up | Sample-accurate seek in the decoder; position shows the seek target immediately |
| Pressing Play after the output device disappeared did nothing | `resumeTrack()` only restarted an existing stream | Stream is reopened when needed |
| Sleep timer died when the app was closed | Lived in the ViewModel | Now lives in the service |

## Settings / Tools that are now real (everything except the EQ)
* Output (Auto / AAudio / OpenSL ES), Exclusive mode – stream is reopened immediately
* Bit-perfect – on: each file at its own sample rate; off: everything resampled to 48 kHz (applies from the next song)
* Direct volume control – in-engine volume, slider in Settings and on Now Playing
* ReplayGain Off / Track / Album (also Opus R128 tags)
* Peak limiter on/off (only engages when gain/bass/width can raise the level, so default playback is untouched)
* Tools → Preamp, Bass boost, Stereo width, Balance
* Tools → Signal shows the real output stream (e.g. "AAudio · Exclusive · 96 kHz")
* Settings → Playback → Notification controls (opens system settings if notifications are blocked)

## Still to do
* Equalizer (UI only; `eqEnabled` / `eqBands` / `eqPreset` are saved but not applied). Plug it into `DspChain::process` in `cpp/Dsp.h`, before the limiter.

---

# Round 2 – Now Playing redesign, full-frame artwork, sectioned Settings

## Now Playing (`ui/screens/NowPlayingScreen.kt`)
One structure, two looks – top bar (close · "Playing from <folder>" · ⋮) → artwork → title / artist →
seek bar + times → transport → [volume] → output line.
* **Full-frame artwork** (default): the cover fills the top of the screen edge-to-edge, fades into a
  background tinted from the cover's own colours (`ui/ArtPalette.kt`), flat Apple-style controls,
  "Hi-Res Lossless / Lossless" badge under the seek bar, quality caption on the cover, drag the cover
  down to close, status-bar icons forced light while it is showing.
* **Framed**: the previous raised-plate look, now on the same structure (left-aligned title block,
  quality chips, ⋮ menu).
* **⋮ menu** (`ui/components/PlayerMenu.kt`): Full-frame artwork on/off · Keep screen on · Sound tools.

## Settings (`ui/screens/SettingsScreen.kt`)
The one long list is now a list of **sections**, each opening its own page of grouped settings:
Appearance · Player · Library · Audio engine · About. Every row on the main list shows what is
currently chosen. System Back (and tapping the Settings tab again) steps out of a section.
* New: Player → **Full-frame artwork** switch (same setting as the ⋮ menu: `SettingsState.fullFrameArt`).

## Other
* `NowPlayingUiState` gained `channelCount`, `bitrateKbps`, `source` (folder name).

---

# Round 3 – backend: DVC done properly, maximum-clarity output for AUX and USB DAC

## Direct volume control (before: a plain software gain on top of Android's volume)
* **Dither + 64-bit maths**: attenuation happens in double precision and is rounded once, at the end, with TPDF dither (`cpp/Quantizer.h`).
* **System volume folded in**: switching DVC on moves Android's current attenuation into the engine level, so loudness never jumps (`playback/DirectVolume.kt`, maths in `OutputPlanner.kt` → `VolumeMath`).
* **System volume parked at maximum while music plays**, put back at equal loudness when it stops/pauses (also after a crash). Optional: Settings → Audio engine → Volume.
* **Hardware volume keys control the DVC level** (media session in remote-volume mode).

## Output path
* **Output format** Auto / Float / 32 / 24 / 16 bit; engine does the final rounding itself (`cpp/Quantizer.h`).
* **Dither** Off / Auto / Always. *Auto* adds it only when the signal was changed or has to be rounded down – an untouched 16-bit file played to a 16-bit stream is bit-exact (verified in `enginetest`).
* **Resampler quality** Standard / High / Maximum (libswresample filter size, cut-off, precision). 20 kHz passband error: 0.50 dB → 0.006 dB → 0.000 dB.
* **Sample-rate planning** (`OutputPlanner.chooseRate`): the file's rate if the output takes it, else the nearest rate of the same family (44.1 → 88.2/176.4, 48 → 96/192). Bit-perfect off = the device's own mixer rate (was a fixed 48 kHz).
* **Accurate seek / position** and normalised stream start time (done in round 1/2, kept).
* Adaptive buffer: grows by one burst whenever the device underruns. Denormal flush in the bass filter.
* Stream open falls back step by step: DAC refused → default device, 24-bit refused → float, etc.

## USB DAC
* `playback/AudioRouteMonitor.kt`: knows where the sound is going (USB DAC / AUX / Bluetooth / HDMI / speaker) and what the device accepts.
* `playback/UsbBitPerfect.kt` (Android 14+): `AudioManager.setPreferredMixerAttributes` with the best bit-perfect format the DAC offers (rate, channels, smallest container that holds the file's bit depth) and a *strict* stream (no conversion, no SRC, shared path). Volume then comes from DVC, which is switched on automatically while it is engaged.
* Plugging / unplugging a DAC re-plans the current song and continues from the same position.
* Settings → Audio engine shows the connected output and whether bit-perfect is available.

## Tests that were run on the host (real FFmpeg, real Oboe headers, fake output device)
`Dsp.h`, `Quantizer.h`, the decoder (FLAC/MP3/Opus), the whole `AudioEngine` (16-bit and 24-bit/96 kHz files are bit-exact at the output; plan → stream request; fallbacks; reopen rules; xrun handling; mid-song resume), and the planner / DVC maths.

---

# Round 4 – the equalizer engine (graphic + parametric)

## Engine (`cpp/Eq.h`, wired into `cpp/Dsp.h` ahead of everything else in the chain)
* One cascade of up to 32 biquads in 64-bit precision, one set of filter memories per channel.
* Filter types: **Peak, Low shelf, High shelf, Low-pass, High-pass, Notch** (RBJ designs, Q-form for shelves — the same convention as Equalizer APO / AutoEQ).
* **Glide, no clicks**: frequency, gain and Q glide to a new value in ~10 ms; switching a band on/off fades through flat; switching the EQ on/off cross-fades. Verified: a 0 → +15 dB jump produces no discontinuity.
* **Auto headroom**: the stage is turned down by the highest point of the combined response (measured on a fine log grid at the real sample rate), so boosting can never clip.
* **Bit-exact when idle**: EQ off, or every band at 0 dB → the stage is skipped entirely (a 16-bit file still comes out identical, verified end to end).
* Real-time safe: parameters reach the audio thread through a sequence-locked set of atomics — no locks, no allocation; verified under ThreadSanitizer, AddressSanitizer and UBSan.

## Settings / model (`audio/EqModel.kt`, `ui/AppSettings.kt`)
* `peqEnabled`, `peqBands` (up to 16), `eqAutoHeadroom`; graphic EQ (`eqEnabled`, `eqBands`) is now applied too.
* The graphic EQ is ten octave bands (31 Hz … 16 kHz, Q 1.41); both equalizers occupy fixed slots of one cascade, so turning one off never disturbs the other.
* Filter maths in Kotlin = the engine's C++ maths to 3·10⁻¹² (checked against values printed from C++), so the curve on screen is exactly what is applied.

## Tools screen
* **Graphic EQ** card (PREVIEW tag removed) now has the Auto-headroom row.
* **Parametric EQ** card, directly below it:
  * live frequency-response graph — drag a dot sideways for frequency, up/down for gain; a touch that does not land on a dot still scrolls the page;
  * band chips (1 · 80 Hz, …, + Add band), type chips, and for each of Frequency / Gain / Q: slider, − / + fine steps (1/12 octave, 0.1 dB, 0.05–0.5 Q) and a field you can tap to **type an exact value** ("2.5k", "-3.5", "0.71");
  * Band on/off, 0 dB reset, Delete; **Paste** (AutoEQ / Equalizer APO text from the clipboard), **Copy** (same format), **Reset**.

---

# Round 5 – housekeeping: docs, database, backup, Gradle wrapper

## Fixed
| Problem | Fix |
|---|---|
| README still said the equalizer was interface-only (note, feature list, Tools tab, roadmap, disclaimer) although Round 4 made it real | All of those now describe the working graphic + parametric EQ; EQ added to the DSP-chain diagram and stage table (it is the first stage); `Eq.h` added to the folder table; roadmap and LOC chart updated |
| Any database schema change wiped the library and the folder list (`fallbackToDestructiveMigration()`) | `exportSchema = true` + `room.schemaLocation` (`app/schemas/`), a `Migrations.ALL` list (`data/Migrations.kt`) and a policy: only the never-released versions 1 and 2 and downgrades may be rebuilt; any other version bump without a migration fails loudly instead of silently deleting data |
| `allowBackup="true"` with no rules would restore the library DB, the last-opened folder (SAF URI) and the Direct-volume state onto another phone, where those are invalid or wrong | `res/xml/backup_rules.xml` + `data_extraction_rules.xml`: only `resonix_ui_settings` is backed up / transferred |
| No `gradlew` in the repository | Official wrapper for Gradle 9.5.0 added (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`); README build section updated |
| Stray 1-byte file `docs/f` | Removed |

## Needs one build on a machine with the Android SDK
* The first `./gradlew :app:assembleDebug` writes `app/schemas/com.resonix.player.data.AppDatabase/3.json`. **Commit it** — it is the baseline every future migration is written and tested against.
* These changes were made without an Android SDK / NDK at hand, so they have not been compiled yet.

---

# Round 6 – gapless playback, honest output rate, ring-buffer fix

All native changes below were compiled against the real Oboe 1.11.0 headers and exercised on a Linux host with the real `AudioEngine`, `AudioDecoder` (FFmpeg) and DSP, driving a stand-in for Oboe by hand: 79 checks pass in a normal build, under AddressSanitizer + UBSan and under ThreadSanitizer (no warnings), 20 repeated runs (5 of them under CPU load) and a separate 2^32-sample ring-buffer run. The Kotlin parts (`PlaybackQueueManager`, `NativeAudioEngine`) were compiled with Kotlin 2.4.20 against stubs for coroutines and Room; the Android UI, service and Gradle changes were only parsed, not built.

## Added
* **Gapless playback** (setting *Gapless playback*, on by default).
  * Engine: two decoders. `prepareNextFd()` opens the following song while the current one plays; when the playing decoder ends, the decode thread carries on with the prepared one and records the exact ring-buffer position where it starts (the *boundary*). The audio callback splits its block at that position, so the ReplayGain of the new song starts on its first sample. `consumeGaplessAdvance()` reports the hand-over; `getAudioTrackInfo()` / `getCurrentPositionMs()` follow the heard audio (the old song's position until the boundary, the new one's after). A seek while the old song is still audible goes back to the old decoder and drops the prepared song; `cancelNext()`, `isNextReady()`.
  * Only when both songs come out as the same stream: the engine refuses a different output rate or channel count; the service additionally requires the same bit depth with the USB bit-perfect mixer (its format was chosen for the first song) and the same sample rate with Bit-perfect output on.
  * Queue manager: prepares the follower after each load, after a shuffle change and after a seek, and moves `nowPlaying` / the queue index when the engine reports the hand-over.
  * Verified sample for sample: output of two songs is identical to the two decoded files back to back.
* **The real device rate** (Android 14+, `getHardwareSampleRate`) is read and shown (`device 96 kHz`).
* **Own resampling instead of Android's**: streams are opened without Oboe's sample-rate conversion (a lenient attempt with it remains the last resort). If the platform will resample (hardware rate differs; the stream came back at another rate; on older Android a shared stream on the speaker / headphone jack whose mixer rate is known), the decoder is reopened for that rate and the stream follows. Remembered per output, so later songs need no extra open. Not applied to the bit-perfect mixer or to exclusive streams the device grants.
* **Signed release workflow** (`.github/workflows/release.yml`) and environment-driven signing in `app/build.gradle.kts`.

## Fixed
* **Ring buffer indices were `size_t`**: on armeabi-v7a they wrapped after about 3 hours of 192 kHz stereo (about 12 hours at 48 kHz) and, because the capacity is not a power of two, put the read position in the wrong place. Now 64-bit everywhere. Copies are two straight `memcpy`s instead of a modulo per sample (less work in the audio callback).
* **Bit-perfect planning assumed 2 channels** for every song; the real channel count is now read from the file (mono / multichannel files no longer ask for a mismatching mixer format).

## Files
Native: `RingBuffer.h`, `AudioEngine.h/.cpp`, `native-lib.cpp`. Kotlin: `NativeAudioEngine.kt`, `PlaybackQueueManager.kt`, `PlaybackService.kt`, `AppSettings.kt`, `SettingsScreen.kt`. Build: `app/build.gradle.kts`, `.github/workflows/release.yml`.

## Not done
* **Queue view and playlists** (needs interface design and a database migration 3 → 4; the migration path from Round 5 is ready for it).
* The direct USB (UAC2, libusb) output path that bypasses AudioFlinger entirely.

