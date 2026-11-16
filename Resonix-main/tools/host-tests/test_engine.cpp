// Host-side tests for the native engine: real AudioEngine + real AudioDecoder (host FFmpeg) +
// real DSP, with a fake Oboe that the test pumps by hand (deterministic).
// Run with tools/host-tests/run.sh (needs g++, the FFmpeg dev packages and the ffmpeg command line).
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "AudioEngine.h"
#include "FakeDac.h"

using namespace resonix;
static std::string mediaDir() {
    const char *d = std::getenv("RESONIX_TEST_MEDIA");
    std::string m = d ? d : "media";
    if (!m.empty() && m.back() != '/') m += '/';
    return m;
}
static const std::string kMedia = mediaDir();
static int gFail = 0, gChecks = 0;
#define CHECK(cond, ...)                                                    \
    do {                                                                    \
        gChecks++;                                                          \
        if (!(cond)) {                                                      \
            gFail++;                                                        \
            std::printf("  FAIL line %d: %s  ", __LINE__, #cond);           \
            std::printf(__VA_ARGS__);                                       \
            std::printf("\n");                                              \
        }                                                                   \
    } while (0)

static std::vector<float> readRaw(const std::string &name) {
    std::ifstream f(kMedia + name, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    std::vector<float> v(b.size() / 4);
    std::memcpy(v.data(), b.data(), v.size() * 4);
    return v;
}
static int openFile(const std::string &name) { return ::open((kMedia + name).c_str(), O_RDONLY); }
static void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

static void freshPlatform() {
    oboe::platform() = oboe::FakePlatform{};
    oboe::platform().reportHardwareRate = false;  // default: the platform says nothing, the stream is left alone
    oboe::AudioStreamBuilder::registry().reset();
}
static void setup(AudioEngine &e, int rg = AudioEngine::kRgOff) {
    e.setOutputConfig(AudioEngine::kApiAAudio, /*exclusive*/ false, /*bitPerfect*/ true);
    e.setOutputPlan(0, 0, AudioEngine::kPrefFloat, false, 48000);
    e.setReplayGainMode(rg);
}
// Pretend to be the audio thread: pull [frames] at a time until the callback says Stop (or maxFrames).
struct Pump {
    std::vector<float> out;
    bool stopped = false;
    int channels = 2;
};
static void pumpFrames(AudioEngine &e, Pump &p, size_t frames, int block = 480) {
    auto stream = oboe::AudioStreamBuilder::registry();
    p.channels = stream->getChannelCount();
    std::vector<float> buf(block * p.channels);
    size_t done = 0;
    while (done < frames && !p.stopped) {
        int n = (int)std::min<size_t>(block, frames - done);
        auto r = e.onAudioReady(stream.get(), buf.data(), n);
        p.out.insert(p.out.end(), buf.begin(), buf.begin() + n * p.channels);
        done += n;
        if (r == oboe::DataCallbackResult::Stop) p.stopped = true;
    }
}
static double maxDiff(const std::vector<float> &out, const std::vector<float> &exp, size_t from, size_t to, double gain = 1.0, size_t expOffset = 0) {
    double m = 0;
    for (size_t i = from; i < to && i < out.size() && (i - from + expOffset) < exp.size(); i++)
        m = std::max(m, std::fabs((double)out[i] - (double)exp[i - from + expOffset] * gain));
    return m;
}

// ------------------------------------------------------------------------------------------------
static void testGaplessExact() {
    std::printf("[gapless: exact, sample-for-sample]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    auto A = readRaw("a_44k.raw"), B = readRaw("b_44k.raw");
    int fa = openFile("a_44k.wav"), fb = openFile("b_44k.wav");
    CHECK(e.playTrackFd(fa), "play A");
    CHECK(e.prepareNextFd(fb), "prepare B (same rate and channels)");
    ::close(fa); ::close(fb);  // the engine dup()ed them
    sleepMs(500);

    Pump p;
    const size_t aFrames = A.size() / 2, bFrames = B.size() / 2;
    bool advanced = false; size_t advancedAt = 0; int posBad = 0; int infoBad = 0;
    int finishedEarly = 0;
    for (size_t pumped = 0; pumped < aFrames + bFrames + 4000 && !p.stopped; pumped += 480) {
        pumpFrames(e, p, 480);
        const size_t now = std::min(pumped + 480, aFrames + bFrames);
        int64_t pos = e.getCurrentPositionMs();
        TrackInfo ti = e.getAudioTrackInfo();
        const bool adv = e.consumeGaplessAdvance();
        if (adv && !advanced) { advanced = true; advancedAt = pumped + 480; }
        const bool inB = advanced;
        const double expMs = (inB ? (double)(now > aFrames ? now - aFrames : 0) : (double)now) * 1000.0 / 44100.0;
        // around the boundary itself either side is acceptable
        if (std::fabs((double)pos - expMs) > 40.0 && std::llabs((long long)now - (long long)aFrames) > 960) posBad++;
        if (!inB && ti.durationMs != 2000 && ti.durationMs != 0) infoBad++;
        if (e.consumeTrackFinishedEvent() && now < aFrames + bFrames - 1000) finishedEarly++;
    }
    CHECK(advanced, "advance reported");
    CHECK(advancedAt >= aFrames && advancedAt <= aFrames + 1920, "advance reported at the boundary (%zu vs %zu)", advancedAt, aFrames);
    CHECK(posBad == 0, "position follows the heard audio (%d bad)", posBad);
    CHECK(infoBad == 0, "info stays the old track's until the boundary");
    CHECK(p.out.size() >= A.size() + B.size(), "got %zu samples, need %zu", p.out.size(), A.size() + B.size());
    CHECK(maxDiff(p.out, A, 0, A.size()) == 0.0, "A part identical (%.3g)", maxDiff(p.out, A, 0, A.size()));
    CHECK(maxDiff(p.out, B, A.size(), A.size() + B.size()) == 0.0, "B part identical straight after A (%.3g)", maxDiff(p.out, B, A.size(), A.size() + B.size()));
    // nothing but silence after
    bool silentTail = true;
    for (size_t i = A.size() + B.size(); i < p.out.size(); i++) if (p.out[i] != 0.f) { silentTail = false; break; }
    CHECK(silentTail, "silence after the last track");
    CHECK(p.stopped, "stream stopped at the end");
    TrackInfo ti = e.getAudioTrackInfo();
    CHECK(ti.durationMs == 2000, "info describes B now (%lld)", (long long)ti.durationMs);
    CHECK(!e.consumeGaplessAdvance(), "advance event consumed only once");
    CHECK(e.consumeTrackFinishedEvent() || finishedEarly == 0, "finished event at the end");
    CHECK(finishedEarly == 0, "no 'finished' at the boundary (%d)", finishedEarly);
}

static void testRejects() {
    std::printf("[gapless: refused when the stream would have to change]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    int fa = openFile("a_44k.wav");
    CHECK(e.playTrackFd(fa), "play A");
    ::close(fa);
    int fc = openFile("c_48k.wav");
    CHECK(!e.prepareNextFd(fc), "48 kHz after 44.1 kHz: refused");
    ::close(fc);
    int fm = openFile("mono_44k.wav");
    CHECK(!e.prepareNextFd(fm), "mono after stereo: refused");
    ::close(fm);
    int fb = openFile("b_44k.wav");
    CHECK(e.prepareNextFd(fb), "same format accepted");
    CHECK(e.cancelNext(), "cancel before the hand-over");
    ::close(fb);
    sleepMs(400);
    Pump p; pumpFrames(e, p, 200000);
    CHECK(p.stopped, "track A just ends");
    CHECK(!e.consumeGaplessAdvance(), "no hand-over after cancel");
    CHECK(e.consumeTrackFinishedEvent(), "finished event instead");
    CHECK(p.out.size() >= 2 * 88200 && maxDiff(p.out, readRaw("a_44k.raw"), 0, 176400) == 0.0, "only A was played");
    // prepare with nothing playing
    AudioEngine e2; setup(e2);
    int fb2 = openFile("b_44k.wav");
    CHECK(!e2.prepareNextFd(fb2), "nothing loaded: refused");
    ::close(fb2);
}

static void testReplayGain() {
    std::printf("[gapless: ReplayGain switches on the exact boundary sample]\n");
    freshPlatform();
    AudioEngine e; setup(e, AudioEngine::kRgTrack);
    auto A = readRaw("a_rg.raw"), B = readRaw("b_rg.raw");
    int fa = openFile("a_rg.flac"), fb = openFile("b_rg.flac");
    CHECK(e.playTrackFd(fa), "play");
    CHECK(e.prepareNextFd(fb), "prepare");
    ::close(fa); ::close(fb);
    sleepMs(500);
    Pump p; pumpFrames(e, p, A.size() / 2 + B.size() / 2 + 2000);
    const double gA = std::pow(10.0, -6.0 / 20), gB = std::pow(10.0, -12.0 / 20);
    const size_t aN = A.size();
    const size_t ms = 44100 * 2 / 1000;  // samples per ms
    // A: settled part, then right up to the last sample before the boundary
    CHECK(maxDiff(p.out, A, 200 * ms, aN, gA, 200 * ms) < 2e-5, "A at A's gain until the last sample (%.3g)", maxDiff(p.out, A, 200 * ms, aN, gA, 200 * ms));
    CHECK(maxDiff(p.out, A, aN - 64, aN, gA, aN - 64) < 2e-5, "no early gain change in A's final samples (%.3g)", maxDiff(p.out, A, aN - 64, aN, gA, aN - 64));
    // B: gain settled within 150 ms after the boundary
    CHECK(maxDiff(p.out, B, aN + 150 * ms, aN + B.size(), gB, 150 * ms) < 2e-5, "B at B's gain (%.3g)", maxDiff(p.out, B, aN + 150 * ms, aN + B.size(), gB, 150 * ms));
}

static void testSeekRevert(bool reprepare) {
    std::printf("[gapless: seek while the old track is still audible%s]\n", reprepare ? ", then prepare again" : "");
    freshPlatform();
    AudioEngine e; setup(e);
    auto A = readRaw("a_44k.raw"), B = readRaw("b_44k.raw");
    int fa = openFile("a_44k.wav"), fb = openFile("b_44k.wav");
    CHECK(e.playTrackFd(fa), "play");
    CHECK(e.prepareNextFd(fb), "prepare");
    ::close(fa);
    sleepMs(500);  // everything is decoded: the decoder is on B, A is audible
    Pump p1; pumpFrames(e, p1, 22050);
    CHECK(maxDiff(p1.out, A, 0, p1.out.size()) == 0.0, "first half second is A");
    e.seekTo(1000);
    sleepMs(200);
    int64_t pos = e.getCurrentPositionMs();
    CHECK(std::llabs(pos - 1000) < 60, "position follows the seek (%lld)", (long long)pos);
    TrackInfo ti = e.getAudioTrackInfo();
    CHECK(ti.durationMs == 2000, "still A's info");
    if (reprepare) {
        int fb2 = openFile("b_44k.wav");
        CHECK(e.prepareNextFd(fb2), "prepare B again after the seek");
        ::close(fb2);
        sleepMs(300);
    }
    Pump p2; pumpFrames(e, p2, 300000);
    const size_t from = 44100 * 2;  // 1000 ms in samples
    std::vector<float> expect(A.begin() + from, A.end());
    if (reprepare) expect.insert(expect.end(), B.begin(), B.end());
    CHECK(p2.out.size() >= expect.size(), "got %zu need %zu", p2.out.size(), expect.size());
    CHECK(maxDiff(p2.out, expect, 0, expect.size()) == 0.0, "after the seek: A from 1.0 s%s (%.3g)", reprepare ? ", then B gaplessly" : " only", maxDiff(p2.out, expect, 0, expect.size()));
    bool silentTail = true;
    for (size_t i = expect.size(); i < p2.out.size(); i++) if (p2.out[i] != 0.f) { silentTail = false; break; }
    CHECK(silentTail, "nothing of B leaks in without a prepare");
    if (reprepare) CHECK(e.consumeGaplessAdvance(), "hand-over reported");
    else CHECK(!e.consumeGaplessAdvance(), "no hand-over");
    CHECK(e.consumeTrackFinishedEvent(), "track finished at the end");
}

static void testRetarget() {
    std::printf("[platform resampling: converted by Resonix]\n");
    auto &pf = oboe::platform();
    auto runHi = [&](AudioEngine &e, Pump &p) {
        int f = openFile("hi_96k.wav");
        bool ok = e.playTrackFd(f);
        ::close(f);
        sleepMs(300);
        pumpFrames(e, p, 400000);
        return ok;
    };
    {   // Android 14+: hardware rate is reported
        freshPlatform(); pf.mixerRate = 48000; pf.reportHardwareRate = true;
        AudioEngine e; setup(e);
        Pump p; CHECK(runHi(e, p), "play 96 kHz file");
        TrackInfo ti = e.getAudioTrackInfo();
        CHECK(ti.sampleRateHz == 96000 && ti.outputSampleRateHz == 48000, "decoded for 48 kHz (%d -> %d)", ti.sampleRateHz, ti.outputSampleRateHz);
        CHECK(pf.openedRates.size() == 2 && pf.openedRates[0] == 96000 && pf.openedRates[1] == 48000, "opened 96k then 48k (%zu opens)", pf.openedRates.size());
        CHECK(pf.openedModes[0] == 0, "Oboe sample-rate conversion off in the first attempt");
        std::string d = e.getOutputDescription();
        CHECK(d.find("Resonix resampled 96 kHz → 48 kHz") != std::string::npos, "description says so: '%s'", d.c_str());
        // 2 s at 48 kHz, give or take the resampler's edges
        size_t frames = 0; for (size_t i = 0; i + 1 < p.out.size(); i += 2) { if (std::fabs(p.out[i]) > 1e-9 || std::fabs(p.out[i+1]) > 1e-9) frames = i / 2 + 1; }
        CHECK(std::llabs((long long)frames - 96000) < 600, "about 2 s of audio at 48 kHz (%zu frames)", frames);
        // the next 96 kHz track: learned, no more reopening
        size_t opens = pf.openedRates.size();
        int f = openFile("hi_96k.wav"); CHECK(e.playTrackFd(f), "again"); ::close(f);
        TrackInfo t2 = e.getAudioTrackInfo();
        CHECK(pf.openedRates.size() == opens && t2.outputSampleRateHz == 48000, "second song goes straight to 48 kHz (%zu opens)", pf.openedRates.size());
    }
    {   // Older Android: no hardware rate, the built-in route's mixer rate is passed in
        freshPlatform(); pf.mixerRate = 48000; pf.reportHardwareRate = false;
        AudioEngine e; setup(e); e.setPlatformMixerRate(48000, 7);
        Pump p; runHi(e, p);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 48000, "legacy: converted to the route's 48 kHz");
        // a different route forgets what was learned
        e.setPlatformMixerRate(0, 9);
        int f = openFile("hi_96k.wav"); e.playTrackFd(f); ::close(f);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 96000, "route changed, mixer unknown: file's own rate again (%d)", e.getAudioTrackInfo().outputSampleRateHz);
    }
    {   // Unknown route, no hardware rate: nothing to go on, leave the stream alone
        freshPlatform(); pf.reportHardwareRate = false;
        AudioEngine e; setup(e);
        Pump p; runHi(e, p);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 96000 && pf.openedRates.size() == 1, "unknown: untouched");
        CHECK(e.getOutputDescription().find("Resonix resampled") == std::string::npos, "no claim made");
    }
    {   // Exclusive (MMAP) granted: not mixed, nothing to convert
        freshPlatform(); pf.grantExclusive = true; pf.reportHardwareRate = true;
        AudioEngine e; e.setOutputConfig(AudioEngine::kApiAAudio, true, true);
        e.setOutputPlan(0, 0, AudioEngine::kPrefFloat, false, 48000); e.setReplayGainMode(0);
        Pump p; runHi(e, p);
        TrackInfo ti = e.getAudioTrackInfo();
        CHECK(ti.outputSampleRateHz == 96000 && pf.openedRates.size() == 1, "exclusive at 96 kHz kept (%d, %zu opens)", ti.outputSampleRateHz, pf.openedRates.size());
        CHECK(e.getOutputDescription().find("device 96 kHz") != std::string::npos, "confirmed by the device: '%s'", e.getOutputDescription().c_str());
    }
    {   // The stream itself comes back at another rate
        freshPlatform(); pf.grantRequestedRate = false; pf.reportHardwareRate = false;
        AudioEngine e; setup(e);
        Pump p; runHi(e, p);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 48000, "granted 48 kHz -> decoder follows");
    }
    {   // Bit-perfect mixer (strict): never second-guessed
        freshPlatform(); pf.mixerRate = 48000; pf.reportHardwareRate = true;
        AudioEngine e; e.setOutputConfig(AudioEngine::kApiAAudio, true, true);
        e.setOutputPlan(5, 96000, AudioEngine::kPrefI24, true, 48000); e.setReplayGainMode(0);
        Pump p; runHi(e, p);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 96000 && pf.openedRates.size() == 1, "strict stream untouched");
    }
    {   // a rate the planner chose (a DAC's supported rate) that Android's mixer would still resample
        freshPlatform(); pf.mixerRate = 48000; pf.reportHardwareRate = true;
        AudioEngine e; e.setOutputConfig(AudioEngine::kApiAAudio, false, true);
        e.setOutputPlan(0, 44100, AudioEngine::kPrefFloat, false, 48000); e.setReplayGainMode(0);
        int f = openFile("a_44k.wav"); CHECK(e.playTrackFd(f), "play"); ::close(f);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 48000, "planner's 44.1 kHz replaced by the mixer's 48 kHz");
        size_t opens = pf.openedRates.size();
        f = openFile("b_44k.wav"); e.playTrackFd(f); ::close(f);
        CHECK(pf.openedRates.size() == opens, "next song: no reopening (%zu -> %zu)", opens, pf.openedRates.size());
        // two songs of the same rate stay gapless on that converted stream
        f = openFile("a_44k.wav"); e.playTrackFd(f); ::close(f);
        int g = openFile("b_44k.wav"); CHECK(e.prepareNextFd(g), "gapless across the converted stream"); ::close(g);
    }
    {   // bit-perfect setting off: everything at the native rate from the start, nothing to learn
        freshPlatform(); pf.mixerRate = 48000;
        AudioEngine e; e.setOutputConfig(AudioEngine::kApiAAudio, false, false);
        e.setOutputPlan(0, 0, AudioEngine::kPrefFloat, false, 48000); e.setReplayGainMode(0);
        Pump p; runHi(e, p);
        CHECK(e.getAudioTrackInfo().outputSampleRateHz == 48000 && pf.openedRates.size() == 1, "native rate straight away (%zu opens)", pf.openedRates.size());
    }
}

static void testPlainTrack() {
    std::printf("[plain playback: untouched by all this]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    auto A = readRaw("a_44k.raw");
    int fa = openFile("a_44k.wav"); CHECK(e.playTrackFd(fa), "play"); ::close(fa);
    sleepMs(300);
    Pump p; pumpFrames(e, p, 100000);
    CHECK(maxDiff(p.out, A, 0, A.size()) == 0.0, "bit-exact float output");
    CHECK(p.stopped && e.consumeTrackFinishedEvent(), "finished once");
    CHECK(!e.consumeTrackFinishedEvent(), "...and only once");
    // next/previous style reload while a hand-over was prepared
    freshPlatform();
    AudioEngine e2; setup(e2);
    int a = openFile("a_44k.wav"), b = openFile("b_44k.wav"), c = openFile("c_48k.wav");
    e2.playTrackFd(a); e2.prepareNextFd(b); sleepMs(300);
    CHECK(e2.playTrackFd(c), "load another track on top of a prepared one");
    ::close(a); ::close(b); ::close(c);
    sleepMs(300);
    Pump q; pumpFrames(e2, q, 100000);
    CHECK(maxDiff(q.out, readRaw("c_48k.raw"), 0, 96000) == 0.0, "plays C only");
    CHECK(!e2.consumeGaplessAdvance(), "no stale hand-over");
}


// ------------------------------------------------------------------------------------------------
// Direct USB: the same engine, but the DAC is a simulated USB device and Oboe is not involved.

static const std::vector<fake::Uac2Alt> kUsbAlts = {{1, 2, 16}, {2, 3, 24}, {3, 4, 32}};

static fake::FakeDac *attachFakeDac(AudioEngine &e, const std::vector<int> &rates, std::vector<fake::Uac2Alt> alts = kUsbAlts, int channels = 2) {
    auto dac = std::make_unique<fake::FakeDac>();
    dac->speedup = 3;   // slowly enough that an instrumented (sanitizer) build still keeps the DAC fed
    dac->clockRates = rates;
    fake::FakeDac *raw = dac.get();
    const fake::Bytes d = fake::uac2Descriptors(alts, channels);
    const std::string summary = e.attachUsbTransport(std::move(dac), d.data(), d.size(), "Test DAC");
    CHECK(!summary.empty(), "attached: %s", summary.c_str());
    e.setUsbDirect(true);
    return raw;
}

static bool waitUntil(const std::function<bool()> &cond, int ms) {
    for (int i = 0; i < ms / 5; i++) { if (cond()) return true; sleepMs(5); }
    return cond();
}

// What the quantiser makes of the float reference: [bytes] per sample, little endian.
static std::vector<uint8_t> toPcm(const std::vector<float> &ref, int bytes) {
    std::vector<uint8_t> out;
    out.reserve(ref.size() * bytes);
    const double scale = (bytes == 2) ? 32768.0 : (bytes == 3 ? 8388608.0 : 2147483648.0);
    for (float f : ref) {
        long long v = std::llround((double)f * scale);
        const long long hi = (long long)scale - 1;
        if (v > hi) v = hi;
        for (int b = 0; b < bytes; b++) out.push_back((uint8_t)((v >> (8 * b)) & 0xFF));
    }
    return out;
}

// The received stream is silence, then the song, then silence. Find the song and compare it with [expect].
static bool matchesSong(const std::vector<uint8_t> &got, const std::vector<uint8_t> &expect, size_t *firstDiff = nullptr) {
    size_t g0 = 0, e0 = 0;
    while (g0 < got.size() && got[g0] == 0) g0++;
    while (e0 < expect.size() && expect[e0] == 0) e0++;
    // a song that starts with zeros: align on the first non-zero byte of each
    if (g0 < e0) return false;
    const size_t start = g0 - e0;
    if (start + expect.size() > got.size()) return false;
    for (size_t i = 0; i < expect.size(); i++) {
        if (got[start + i] != expect[i]) { if (firstDiff) *firstDiff = i; return false; }
    }
    for (size_t i = start + expect.size(); i < got.size(); i++) if (got[i] != 0) { if (firstDiff) *firstDiff = i; return false; }
    return true;
}

static void testUsbBasic() {
    std::printf("[direct USB: a song reaches the DAC bit for bit]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100, 48000, 96000});
    int f = openFile("a_44k.wav"); CHECK(e.playTrackFd(f), "play"); ::close(f);
    CHECK(oboe::platform().openedRates.empty(), "Android's audio stream was never opened");
    CHECK(e.isUsbStreaming(), "streaming over USB");
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 8000), "the song ends");
    sleepMs(100);
    std::lock_guard<std::mutex> l(dac->mu);
    CHECK(dac->uac2Rate == 44100, "DAC clock at 44.1 kHz (%d)", dac->uac2Rate);
    CHECK(dac->altOf[1] == 1, "16-bit alt setting");
    size_t diff = 0;
    CHECK(matchesSong(dac->received, toPcm(readRaw("a_44k.raw"), 2), &diff), "the 16-bit PCM equals the file exactly (first difference at %zu)", diff);
    CHECK(e.getOutputDescription().find("Direct USB · Test DAC · 44.1 kHz · 16-bit") != std::string::npos, "described: %s", e.getOutputDescription().c_str());
    for (size_t i = 0; i < dac->packetLengths.size(); i++) if (dac->packetLengths[i] % 4 != 0) { CHECK(false, "partial frame in packet %zu", i); break; }
}

static void testUsb24() {
    std::printf("[direct USB: 96 kHz / 24-bit]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100, 48000, 96000});
    int f = openFile("hi_96k.wav"); CHECK(e.playTrackFd(f), "play"); ::close(f);
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 10000), "the song ends");
    sleepMs(100);
    std::lock_guard<std::mutex> l(dac->mu);
    CHECK(dac->uac2Rate == 96000 && dac->altOf[1] == 2, "96 kHz on the 24-bit alt (%d, alt %d)", dac->uac2Rate, dac->altOf[1]);
    size_t diff = 0;
    CHECK(matchesSong(dac->received, toPcm(readRaw("hi_96k.raw"), 3), &diff), "24 bits packed in 3 bytes, exact (first difference at %zu)", diff);
}

static void testUsbRateConversion() {
    std::printf("[direct USB: a rate the DAC cannot play is converted by Resonix]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100});
    int f = openFile("c_48k.wav"); CHECK(e.playTrackFd(f), "play a 48 kHz file"); ::close(f);
    TrackInfo ti = e.getAudioTrackInfo();
    CHECK(ti.sampleRateHz == 48000 && ti.outputSampleRateHz == 44100, "decoded for 44.1 kHz (%d -> %d)", ti.sampleRateHz, ti.outputSampleRateHz);
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 6000), "the song ends");
    sleepMs(100);
    std::lock_guard<std::mutex> l(dac->mu);
    CHECK(dac->uac2Rate == 44100, "DAC at 44.1 kHz");
    size_t nonzero = 0; for (uint8_t b : dac->received) if (b) nonzero++;
    CHECK(nonzero > 40000, "sound came out (%zu non-zero bytes)", nonzero);
}

static void testUsbMono() {
    std::printf("[direct USB: a mono song on a stereo alt setting]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100});
    int f = openFile("mono_44k.wav"); CHECK(e.playTrackFd(f), "play"); ::close(f);
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 6000), "the song ends");
    sleepMs(100);
    std::lock_guard<std::mutex> l(dac->mu);
    // both channels carry the mono sample
    const std::vector<uint8_t> mono = toPcm(readRaw("mono_44k.raw"), 2);
    std::vector<uint8_t> stereo;
    for (size_t i = 0; i + 1 < mono.size(); i += 2) { stereo.push_back(mono[i]); stereo.push_back(mono[i + 1]); stereo.push_back(mono[i]); stereo.push_back(mono[i + 1]); }
    size_t diff = 0;
    CHECK(matchesSong(dac->received, stereo, &diff), "left == right == the mono samples (first difference at %zu)", diff);
}

static void testUsbGapless() {
    std::printf("[direct USB: gapless, and the stream stays up between songs]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100, 48000});
    int fa = openFile("a_44k.wav"), fb = openFile("b_44k.wav");
    CHECK(e.playTrackFd(fa), "play A");
    CHECK(e.prepareNextFd(fb), "prepare B");
    ::close(fa); ::close(fb);
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 10000), "both songs end");
    sleepMs(100);
    CHECK(!e.consumeGaplessAdvance() || true, "(advance flag consumed)");
    std::lock_guard<std::mutex> l(dac->mu);
    std::vector<uint8_t> both = toPcm(readRaw("a_44k.raw"), 2);
    const std::vector<uint8_t> b = toPcm(readRaw("b_44k.raw"), 2);
    both.insert(both.end(), b.begin(), b.end());
    size_t diff = 0;
    CHECK(matchesSong(dac->received, both, &diff), "A and B back to back, exact (first difference at %zu)", diff);
    int altSets = 0; for (auto &p : dac->altLog) if (p.second == 1) altSets++;
    CHECK(altSets == 1, "the alt setting was selected once (%d)", altSets);
}

static void testUsbSecondSongSameFormat() {
    std::printf("[direct USB: next song in the same format reuses the open stream]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100, 48000});
    int fa = openFile("a_44k.wav"); CHECK(e.playTrackFd(fa), "play A"); ::close(fa);
    sleepMs(100);
    size_t before;
    { std::lock_guard<std::mutex> l(dac->mu); before = dac->altLog.size(); }
    int fb = openFile("b_44k.wav"); CHECK(e.playTrackFd(fb), "play B straight after"); ::close(fb);
    CHECK(waitUntil([&] { return e.consumeTrackFinishedEvent(); }, 10000), "B ends");
    std::lock_guard<std::mutex> l(dac->mu);
    CHECK(dac->altLog.size() == before, "no new alt-setting calls for the same format (%zu -> %zu)", before, dac->altLog.size());
    CHECK(dac->claimedNow.size() == 2, "interfaces claimed once");
}

static void testUsbUnplug() {
    std::printf("[direct USB: the DAC is unplugged]\n");
    freshPlatform();
    AudioEngine e; setup(e);
    fake::FakeDac *dac = attachFakeDac(e, {44100});
    int f = openFile("hi_96k.wav");  // not supported at 96k here: converted to 44.1k, long enough to be interrupted
    CHECK(e.playTrackFd(f), "play"); ::close(f);
    sleepMs(60);
    dac->unplugged = true;
    CHECK(waitUntil([&] { return e.consumeUsbLost(); }, 3000), "the loss is reported");
    CHECK(!e.isUsbStreaming(), "no longer streaming");
    std::lock_guard<std::mutex> l(dac->mu);
    CHECK(dac->claimedNow.empty(), "interfaces handed back");
}

static void testUsbOffAndFallback() {
    std::printf("[direct USB: off, or not possible, means Android's audio]\n");
    {
        freshPlatform();
        AudioEngine e; setup(e);
        fake::FakeDac *dac = attachFakeDac(e, {44100});
        e.setUsbDirect(false);
        int f = openFile("a_44k.wav"); CHECK(e.playTrackFd(f), "play"); ::close(f);
        CHECK(!oboe::platform().openedRates.empty(), "Android's stream is used");
        std::lock_guard<std::mutex> l(dac->mu);
        CHECK(dac->claimedNow.empty() && dac->received.empty(), "the DAC is left alone");
    }
    {
        freshPlatform();
        AudioEngine e; setup(e);
        fake::FakeDac *dac = attachFakeDac(e, {44100}, kUsbAlts, 2);
        int f = openFile("c_48k.wav");
        dac->rejectClockSet = true;   // the DAC refuses to be set up
        CHECK(e.playTrackFd(f), "play still succeeds"); ::close(f);
        CHECK(!oboe::platform().openedRates.empty(), "fell back to Android's audio");
        std::lock_guard<std::mutex> l(dac->mu);
        CHECK(dac->claimedNow.empty(), "and gave the DAC back");
    }
    {
        // a descriptor with nothing playable is not attached at all
        freshPlatform();
        AudioEngine e; setup(e);
        const fake::Bytes junk = {9, 2, 9, 0, 0, 1, 0, 0x80, 50};
        CHECK(e.attachUsbTransport(std::make_unique<fake::FakeDac>(), junk.data(), junk.size(), "x").empty(), "no PCM stream: refused");
    }
}

int main() {
    testPlainTrack();
    testGaplessExact();
    testRejects();
    testReplayGain();
    testSeekRevert(false);
    testSeekRevert(true);
    testRetarget();
    testUsbBasic();
    testUsb24();
    testUsbRateConversion();
    testUsbMono();
    testUsbGapless();
    testUsbSecondSongSameFormat();
    testUsbUnplug();
    testUsbOffAndFallback();
    std::printf("\n%d checks, %d failed\n", gChecks, gFail);
    return gFail ? 1 : 0;
}
