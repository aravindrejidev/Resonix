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
