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
