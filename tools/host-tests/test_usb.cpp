// Host tests for the direct USB audio driver: descriptor parsing, format / rate choice, packet sizes,
// the streaming thread against a simulated DAC (feedback, underrun-free ordering, unplugging), and the
// order in which UsbDirect sets a DAC up and gives it back.
#include <cmath>
#include <cstdio>
#include <set>

#include "FakeDac.h"
#include "UsbAudio.h"
#include "UsbDirect.h"
#include "UsbStreamer.h"

using namespace resonix::usb;
using fake::FakeDac;

static int gFail = 0, gChecks = 0;
#define CHECK(cond, ...)                                          \
    do {                                                          \
        gChecks++;                                                \
        if (!(cond)) {                                            \
            gFail++;                                              \
            std::printf("  FAIL line %d: %s  ", __LINE__, #cond); \
            std::printf(__VA_ARGS__);                             \
            std::printf("\n");                                    \
        }                                                         \
    } while (0)

static DeviceCaps parseOrDie(const fake::Bytes &d, bool *ok = nullptr) {
    DeviceCaps caps;
    const bool r = parseDescriptors(d.data(), d.size(), &caps);
    if (ok) *ok = r;
    return caps;
}

static const std::vector<fake::Uac2Alt> kAlts = {{1, 2, 16}, {2, 3, 24}, {3, 4, 32}, {4, 4, 24}};

static void testParseUac2() {
    std::printf("[descriptors: UAC2 high speed]\n");
    FakeDac dac;
    bool ok = false;
    DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts), &ok);
    CHECK(ok && caps.uac == 2, "parsed as UAC2");
    CHECK(caps.controlInterface == 0, "control interface %d", caps.controlInterface);
    CHECK(caps.formats.size() == 4, "4 alt settings (%zu)", caps.formats.size());
    if (caps.formats.size() == 4) {
        const StreamFormat &f = caps.formats[1];
        CHECK(f.interfaceNumber == 1 && f.altSetting == 2 && f.channels == 2 && f.subslotBytes == 3 && f.bitResolution == 24, "alt 2 is 24-bit in 3 bytes");
        CHECK(f.endpoint == 0x01 && f.maxPacketBytes == 400 && f.interval == 1, "data endpoint");
        CHECK(f.sync == SyncType::kAsync && f.feedbackEndpoint == 0x81 && f.feedbackMaxPacketBytes == 4 && f.feedbackInterval == 4, "async with feedback endpoint");
        CHECK(f.clockId == 1, "runs on clock 1");
        CHECK(f.rates.empty(), "UAC2 rates are not in the descriptors");
    }
    queryClockRates(dac, &caps);
    CHECK(caps.rates() == dac.clockRates, "rates from the clock: %zu", caps.rates().size());
    CHECK(caps.formats[0].rates == dac.clockRates, "every alt gets them");
    CHECK(!caps.hasCapture, "no microphone");
    CHECK(describe(caps).find("UAC2") != std::string::npos, "described: %s", describe(caps).c_str());
    bool ok2 = true;
    DeviceCaps withMic = parseOrDie(fake::uac2Descriptors(kAlts, 2, true), &ok2);
    CHECK(ok2 && withMic.hasCapture, "a microphone stream is noticed (Android then wants RECORD_AUDIO)");
}

static void testParseUac1() {
    std::printf("[descriptors: UAC1 full speed]\n");
    bool ok = false;
    DeviceCaps caps = parseOrDie(fake::uac1Descriptors({{1, 2, 2, 16, {44100, 48000}, false},
                                                       {2, 2, 3, 24, {44100, 48000, 96000}, true},
                                                       {3, 2, 2, 16, {}, false}}), &ok);
    CHECK(ok && caps.uac == 1 && caps.formats.size() == 3, "3 alts (%zu)", caps.formats.size());
    if (caps.formats.size() == 3) {
        CHECK(caps.formats[0].sync == SyncType::kAdaptive && caps.formats[0].feedbackEndpoint == 0, "alt 1 adaptive, no feedback");
        CHECK(caps.formats[0].rates == (std::vector<int>{44100, 48000}), "discrete rates");
        CHECK(caps.formats[1].sync == SyncType::kAsync && caps.formats[1].feedbackEndpoint == 0x81, "alt 2 async, sync endpoint 0x81");
        CHECK(caps.formats[1].subslotBytes == 3 && caps.formats[1].bitResolution == 24, "24 bit");
        const std::vector<int> expect = {8000, 11025, 16000, 22050, 32000, 44100, 48000};
        CHECK(caps.formats[2].rates == expect, "continuous 8-48 kHz becomes the standard rates inside (%zu)", caps.formats[2].rates.size());
    }
}

static void testImplicitFeedback() {
    std::printf("[descriptors: implicit feedback is refused]\n");
    bool ok = true;
    DeviceCaps caps = parseOrDie(fake::uac2Descriptors({{1, 2, 16, false, 0x05}}, 2, true), &ok);
    CHECK(!ok && caps.formats.empty(), "async + no feedback endpoint + implicit capture: not supported");
    DeviceCaps caps2 = parseOrDie(fake::uac2Descriptors({{1, 2, 16, false, 0x09}}, 2, true), &ok);
    CHECK(ok && caps2.formats.size() == 1, "an adaptive endpoint is fine");
}

static void testChoose() {
    std::printf("[choosing a format]\n");
    FakeDac dac;
    DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts));
    queryClockRates(dac, &caps);
    bool dup = false;
    auto alt = [&](int rate, int ch, int bits) { const StreamFormat *f = chooseFormat(caps, rate, ch, bits, &dup); return f ? f->altSetting : -1; };
    CHECK(alt(44100, 2, 16) == 1, "16-bit file -> 16-bit alt");
    CHECK(alt(44100, 2, 24) == 2, "24-bit file -> 24 bits in 3 bytes (smaller than 24 in 4)");
    CHECK(alt(96000, 2, 32) == 3, "32-bit file -> 32-bit alt");
    CHECK(alt(96000, 2, 0) == 2, "lossy / unknown -> 24-bit");
    CHECK(alt(96000, 2, 8) == 1, "8-bit source fits 16");
    CHECK(alt(22050, 2, 16) == -1, "rate the DAC does not have");
    CHECK(alt(48000, 6, 16) == -1, "channel count the DAC does not have");
    CHECK(alt(48000, 1, 16) == 1 && dup, "mono goes to the stereo alt, duplicated");
    CHECK(nearestSupportedRate(caps, 44100) == 44100, "exact");
    CHECK(nearestSupportedRate(caps, 22050) == 44100, "multiple");
    CHECK(nearestSupportedRate(caps, 384000) == 192000, "nothing above: the highest");
    CHECK(nearestSupportedRate(caps, 32000) == 96000, "lowest integer multiple");
    // only 16 and 24-in-4: a 32-bit file gets the most bits there are
    DeviceCaps small = parseOrDie(fake::uac2Descriptors({{1, 2, 16}, {2, 4, 24}}));
    queryClockRates(dac, &small);
    const StreamFormat *f = chooseFormat(small, 44100, 2, 32, &dup);
    CHECK(f && f->altSetting == 2, "no 32-bit alt: the 24-bit one");
}

static void testPlanner() {
    std::printf("[packet sizes]\n");
    {
        PacketPlanner p(44100, 8000, 1, 100);
        long sum = 0; int lo = 1000, hi = 0;
        for (int i = 0; i < 8000 * 10; i++) { int n = p.next(); sum += n; lo = std::min(lo, n); hi = std::max(hi, n); }
        CHECK(sum == 441000, "exactly 44100 frames per second (%ld in 10 s)", sum);
        CHECK(lo == 5 && hi == 6, "5 or 6 frames per microframe (%d..%d)", lo, hi);
    }
    {
        PacketPlanner p(48000, 1000, 1, 100);
        long sum = 0; for (int i = 0; i < 1000; i++) sum += p.next();
        CHECK(sum == 48000, "full speed: 48 per frame");
        PacketPlanner q(44100, 1000, 1, 100);
        sum = 0; int lo = 100, hi = 0;
        for (int i = 0; i < 10000; i++) { int n = q.next(); sum += n; lo = std::min(lo, n); hi = std::max(hi, n); }
        CHECK(sum == 441000 && lo == 44 && hi == 45, "44.1 kHz at full speed: 44 / 45 (%ld, %d..%d)", sum, lo, hi);
    }
    {
        PacketPlanner p(44100, 8000, 4, 100);  // bInterval 3: one packet every 4 microframes
        long sum = 0; for (int i = 0; i < 2000 * 10; i++) sum += p.next();
        CHECK(sum == 441000, "bInterval 3: 22.05 frames per packet, exact");
    }
    {
        PacketPlanner p(44100, 8000, 1, 5);
        CHECK(p.next() == 5 && p.next() == 5, "never more than the endpoint can carry");
    }
    {
        PacketPlanner p(44100, 8000, 1, 100);
        CHECK(!p.setFeedback(361267 * 2, 4), "twice the rate: refused");
        CHECK(!p.setFeedback(0, 4), "zero refused");
        CHECK(p.setFeedback(361268, 4), "16.16 accepted");
        CHECK(p.setFeedback(90317, 3), "10.14 accepted (3 bytes)");
        CHECK(std::fabs(p.feedbackRateHz() - 44100.0) < 1.0, "rate %f", p.feedbackRateHz());
        const uint32_t v = (uint32_t)(44144.1 / 8000.0 * 65536.0 + 0.5);
        CHECK(p.setFeedback(v, 4), "device slightly fast");
        long sum = 0; for (int i = 0; i < 8000 * 10; i++) sum += p.next();
        CHECK(std::llabs(sum - 441441) <= 2, "follows the device: %ld frames in 10 s", sum);
        CHECK(p.setFeedback(v >> 2, 4), "a device that sends 10.14 in 4 bytes is read correctly");
    }
}

// Fill: each frame carries its own number (16-bit, both channels), so a lost, repeated or reordered
// frame shows up as a break in the count.
struct Ramp {
    std::atomic<uint32_t> next{0};
    int frameBytes = 4;
    void operator()(uint8_t *dst, int frames) {
        for (int i = 0; i < frames; i++) {
            const uint16_t v = (uint16_t)next.fetch_add(1);
            for (int b = 0; b < frameBytes; b += 2) { dst[i * frameBytes + b] = v & 0xFF; dst[i * frameBytes + b + 1] = v >> 8; }
        }
    }
};

static bool rampContinuous(const std::vector<uint8_t> &data, int frameBytes, size_t *brokenAt = nullptr) {
    const size_t frames = data.size() / frameBytes;
    for (size_t i = 0; i < frames; i++) {
        const uint16_t v = data[i * frameBytes] | (data[i * frameBytes + 1] << 8);
        if (v != (uint16_t)i) { if (brokenAt) *brokenAt = i; return false; }
    }
    return true;
}

static StreamFormat firstFormat(DeviceCaps &caps, int alt) {
    for (auto &f : caps.formats) if (f.altSetting == alt) return f;
    return {};
}

static UsbStreamer::Params paramsFor(const StreamFormat &f, int rate) {
    UsbStreamer::Params p;
    p.sampleRate = rate; p.frameBytes = f.frameBytes(); p.endpoint = f.endpoint; p.maxPacketBytes = f.maxPacketBytes;
    p.interval = f.interval; p.feedbackEndpoint = f.sync == SyncType::kAsync ? f.feedbackEndpoint : 0;
    p.feedbackMaxPacketBytes = f.feedbackMaxPacketBytes ? f.feedbackMaxPacketBytes : 4;
    return p;
}

static void runStream(FakeDac &dac, const StreamFormat &f, int rate, double seconds, Ramp &ramp, UsbStreamer::Stats *stats = nullptr) {
    ramp.frameBytes = f.frameBytes();
    UsbStreamer s(dac);
    CHECK(s.start(paramsFor(f, rate), [&](uint8_t *d, int n) { ramp(d, n); }), "start");
    while (dac.virtualNow() < seconds) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (stats) *stats = s.stats();
    s.stop();
}

static void testStreaming() {
    std::printf("[streaming against the simulated DAC]\n");
    {   // async with explicit feedback, DAC clock 0.1 % fast
        FakeDac dac; dac.speedup = 6; dac.deviceRateHz = 44144.1;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts)); queryClockRates(dac, &caps);
        StreamFormat f = firstFormat(caps, 1);
        Ramp ramp; UsbStreamer::Stats st;
        runStream(dac, f, 44100, 3.0, ramp, &st);
        size_t at = 0;
        CHECK(rampContinuous(dac.received, f.frameBytes(), &at), "no frame lost, repeated or reordered (broke at %zu of %zu)", at, dac.received.size() / f.frameBytes());
        bool sizesOk = true; for (uint32_t len : dac.packetLengths) if (len % f.frameBytes() != 0 || len > (uint32_t)f.maxPacketBytes) sizesOk = false;
        CHECK(sizesOk, "every packet is whole frames and fits the endpoint");
        const double hz = dac.received.size() / (double)f.frameBytes() / dac.packetSeconds();
        CHECK(std::fabs(hz - 44144.1) / 44144.1 < 0.002, "followed the DAC's feedback: %.1f Hz (device 44144.1)", hz);
        CHECK(st.feedbackHz > 44000 && std::fabs(st.feedbackHz - 44144.1) < 5.0, "reported feedback %.1f", st.feedbackHz);
        CHECK(dac.maxSeenInFlight >= UsbStreamer::kOutTransfers, "a queue of %d transfers (%d)", UsbStreamer::kOutTransfers, dac.maxSeenInFlight);
    }
    {   // adaptive: exactly nominal, no feedback
        FakeDac dac; dac.speedup = 6;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors({{1, 2, 16, false, 0x09}})); queryClockRates(dac, &caps);
        StreamFormat f = firstFormat(caps, 1);
        Ramp ramp; runStream(dac, f, 44100, 2.0, ramp);
        CHECK(rampContinuous(dac.received, f.frameBytes()), "continuous");
        const double hz = dac.received.size() / (double)f.frameBytes() / dac.packetSeconds();
        CHECK(std::fabs(hz - 44100.0) / 44100.0 < 0.0005, "nominal rate %.2f Hz", hz);
    }
    {   // full speed UAC1, adaptive, 44.1 kHz = 44/45 frames per millisecond
        FakeDac dac; dac.speedup = 6; dac.speedValue = 2;
        DeviceCaps caps = parseOrDie(fake::uac1Descriptors({{1, 2, 2, 16, {44100, 48000}, false}}));
        StreamFormat f = firstFormat(caps, 1);
        Ramp ramp; runStream(dac, f, 44100, 2.0, ramp);
        CHECK(rampContinuous(dac.received, f.frameBytes()), "continuous");
        std::set<uint32_t> sizes(dac.packetLengths.begin(), dac.packetLengths.end());
        CHECK(sizes == (std::set<uint32_t>{176, 180}), "44 or 45 frames of 4 bytes per packet");
    }
    {   // full speed async with a 3-byte 10.14 feedback
        FakeDac dac; dac.speedup = 6; dac.speedValue = 2; dac.feedbackBytes = 3; dac.deviceRateHz = 48010.0; dac.feedbackIntervalUnits = 8;
        DeviceCaps caps = parseOrDie(fake::uac1Descriptors({{2, 2, 3, 24, {44100, 48000}, true}}));
        StreamFormat f = firstFormat(caps, 2);
        Ramp ramp; runStream(dac, f, 48000, 3.0, ramp);
        std::lock_guard<std::mutex> l(dac.mu);
        const double hz = dac.received.size() / (double)f.frameBytes() / dac.packetSeconds();
        CHECK(std::fabs(hz - 48010.0) / 48010.0 < 0.002, "10.14 feedback followed: %.1f Hz", hz);
        CHECK(f.frameBytes() == 6, "24-bit stereo in 3 bytes = 6 bytes per frame");
    }
    {   // start / stop many times
        FakeDac dac; dac.speedup = 20;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts)); queryClockRates(dac, &caps);
        StreamFormat f = firstFormat(caps, 1);
        for (int i = 0; i < 15; i++) {
            UsbStreamer s(dac);
            Ramp r; r.frameBytes = f.frameBytes();
            s.start(paramsFor(f, 44100), [&](uint8_t *d, int n) { r(d, n); });
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
            s.stop();
        }
        CHECK(true, "15 start / stop cycles without hanging");
    }
    {   // the device is unplugged
        FakeDac dac; dac.speedup = 6;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts)); queryClockRates(dac, &caps);
        StreamFormat f = firstFormat(caps, 1);
        UsbStreamer s(dac); Ramp r; r.frameBytes = f.frameBytes();
        s.start(paramsFor(f, 44100), [&](uint8_t *d, int n) { r(d, n); });
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        CHECK(!s.failed(), "healthy so far");
        dac.unplugged = true;
        for (int i = 0; i < 100 && !s.failed(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
        CHECK(s.failed(), "unplugging is noticed");
        s.stop();
    }
}

static void testDirect() {
    std::printf("[UsbDirect: setting the DAC up and handing it back]\n");
    auto make = [&](FakeDac *&dacOut) {
        auto dac = std::make_unique<FakeDac>();
        dac->speedup = 8;
        dacOut = dac.get();
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts));
        queryClockRates(*dac, &caps);
        auto d = std::make_unique<UsbDirect>(std::move(dac), caps, "Test DAC");
        d->setSettleMs(0);
        return d;
    };
    FakeDac *dac = nullptr;
    {
        auto d = make(dac);
        Ramp ramp;
        UsbDirect::OpenResult res;
        CHECK(d->open(96000, 2, 24, [&](uint8_t *p, int n) { ramp.frameBytes = 6; ramp(p, n); }, &res), "open 96 kHz / 24-bit");
        CHECK(res.format && res.format->altSetting == 2, "24-bit alt");
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        {
            std::lock_guard<std::mutex> l(dac->mu);
            CHECK(dac->uac2Rate == 96000, "clock set to 96 kHz (%d)", dac->uac2Rate);
            CHECK(dac->altLog.size() >= 2 && dac->altLog[0] == std::make_pair(1, 0) && dac->altLog[1] == std::make_pair(1, 2), "alt 0 first, then the format's alt");
            CHECK(dac->claimedNow.size() == 2, "control and streaming interfaces claimed");
            bool clockBeforeAlt = false;
            for (size_t i = 0; i < dac->controlLog.size(); i++) if (dac->controlLog[i].rfind("21 01 0100 0100", 0) == 0) clockBeforeAlt = true;
            CHECK(clockBeforeAlt, "SET_CUR to clock 1 via interface 0");
        }
        CHECK(d->matches(96000, 2, 24) && !d->matches(48000, 2, 24), "same stream recognised");
        CHECK(d->describeOpen().find("96 kHz") != std::string::npos && d->describeOpen().find("async (feedback)") != std::string::npos, "described: %s", d->describeOpen().c_str());
        CHECK(d->open(44100, 2, 16, [&](uint8_t *p, int n) { ramp.frameBytes = 4; ramp(p, n); }, &res) && res.format->altSetting == 1, "reopen at 44.1 kHz / 16-bit");
        {
            std::lock_guard<std::mutex> l(dac->mu);
            CHECK(dac->uac2Rate == 44100, "clock now 44.1 kHz");
            CHECK(dac->claimedNow.size() == 2, "interfaces claimed once, not twice (%zu)", dac->claimedNow.size());
        }
        d->close();
        { std::lock_guard<std::mutex> l(dac->mu); CHECK(dac->altOf[1] == 0, "switched off on close"); }
        d->releaseInterfaces();
        { std::lock_guard<std::mutex> l(dac->mu);
          CHECK(dac->claimedNow.empty() && dac->releasedLog.size() == 2 && dac->releasedLog[0] == 1 && dac->releasedLog[1] == 0, "streaming interface released first, control interface last (kernel driver binds through it)"); }
    }
    {
        auto d = make(dac);
        dac->rejectClockSet = true;
        Ramp ramp; UsbDirect::OpenResult res;
        CHECK(!d->open(96000, 2, 24, [&](uint8_t *p, int n) { ramp(p, n); }, &res), "a DAC that refuses the rate is not used");
        CHECK(!d->isOpen(), "and stays closed");
    }
    {
        auto d = make(dac);
        dac->clockClaimRate = 48000;
        Ramp ramp; UsbDirect::OpenResult res;
        CHECK(!d->open(96000, 2, 24, [&](uint8_t *p, int n) { ramp(p, n); }, &res), "a clock that reads back another rate is not trusted");
    }
    {
        auto d = make(dac);
        Ramp ramp; UsbDirect::OpenResult res;
        CHECK(!d->open(22050, 2, 16, [&](uint8_t *p, int n) { ramp(p, n); }, &res), "unsupported rate: not opened");
        CHECK(!d->open(48000, 6, 16, [&](uint8_t *p, int n) { ramp(p, n); }, &res), "unsupported channel count: not opened");
    }
}

static void testVolume() {
    std::printf("[the DAC's own volume control]\n");
    {
        FakeDac dac;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts, 2, false, 400, true));
        CHECK(caps.volume.present && caps.volume.unitId == 4 && caps.volume.channels == std::vector<int>{0}, "UAC2: feature unit 4, master channel");
        CHECK(!caps.volume.rangeKnown, "range not read before the DAC is asked");
        queryVolumeRange(dac, &caps, caps.controlInterface);
        CHECK(caps.volume.rangeKnown && caps.volume.min256 == -60 * 256 && caps.volume.max256 == 0 && caps.volume.res256 == 128, "range -60..0 dB step 0.5 (%d..%d step %d)", caps.volume.min256, caps.volume.max256, caps.volume.res256);
        CHECK(caps.volume.haveOriginal && caps.volume.original256 == -3 * 256, "remembers where the DAC was");
        CHECK(describeDetailed(caps).find("volume control: unit 4") != std::string::npos, "shown in the report");
    }
    {
        FakeDac dac; dac.volMin256 = -100 * 256; dac.volMax256 = 12 * 256; dac.volRes256 = 64;
        DeviceCaps caps = parseOrDie(fake::uac1Descriptors({{1, 2, 2, 16, {44100, 48000}, false}}, true));
        CHECK(caps.volume.present && caps.volume.unitId == 3 && caps.volume.uac == 1, "UAC1: feature unit 3");
        queryVolumeRange(dac, &caps, caps.controlInterface);
        CHECK(caps.volume.rangeKnown && caps.volume.max256 == 12 * 256 && caps.volume.res256 == 64, "UAC1 GET_MIN / MAX / RES");
    }
    {
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts));
        CHECK(!caps.volume.present, "a DAC without one is recognised");
    }
    {   // planning: how much the DAC does, how much is left for software
        FakeDac dac;
        auto transport = std::make_unique<FakeDac>();
        FakeDac *raw = transport.get();
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts, 2, false, 400, true));
        UsbDirect d(std::move(transport), caps, "Vol DAC");
        d.setSettleMs(0);
        CHECK(d.prepare() && d.hasHardwareVolume(), "prepared, volume known");
        double residual = 1;
        CHECK(d.planVolume256(0.0, &residual) == 0 && std::fabs(residual) < 1e-9, "0 dB: the DAC at 0 dB, nothing left");
        CHECK(d.planVolume256(-20.0, &residual) == -20 * 256 && std::fabs(residual) < 1e-9, "-20 dB entirely in the DAC");
        CHECK(d.planVolume256(-80.0, &residual) == -60 * 256 && std::fabs(residual + 20.0) < 1e-6, "-80 dB: the DAC to its minimum, -20 dB for software (%.3f)", residual);
        const int snapped = d.planVolume256(-20.2, &residual);
        CHECK(snapped % 128 == 0 && std::fabs(snapped / 256.0 + 20.2) <= 0.26 && std::fabs(residual) <= 0.26, "snapped to the 0.5 dB step: %d, rest %.3f", snapped, residual);
        // blocking set and the async path
        CHECK(d.setHardwareVolumeBlocking(-20.0, &residual), "set");
        d.requestHardwareVolume(-30.0);
        d.requestHardwareVolume(-31.0);
        for (int i = 0; i < 200 && raw->volume() != -31 * 256; i++) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        CHECK(raw->volume() == -31 * 256, "the latest request reaches the DAC (%d)", raw->volume());
        d.releaseInterfaces();
        CHECK(raw->volume() == -3 * 256, "put back to where it was (%d)", raw->volume());
    }
    {   // a DAC whose maximum is below 0 dB: its maximum is "unity"
        auto transport = std::make_unique<FakeDac>();
        transport->volMin256 = -60 * 256; transport->volMax256 = -6 * 256;
        DeviceCaps caps = parseOrDie(fake::uac2Descriptors(kAlts, 2, false, 400, true));
        UsbDirect d(std::move(transport), caps, "Quiet DAC");
        d.prepare();
        double residual = 1;
        CHECK(d.planVolume256(0.0, &residual) == -6 * 256 && std::fabs(residual) < 1e-9, "unity = the DAC's maximum");
        CHECK(d.planVolume256(-10.0, &residual) == -16 * 256, "-10 dB below that");
    }
}

int main() {
    testParseUac2();
    testParseUac1();
    testImplicitFeedback();
    testChoose();
    testPlanner();
    testStreaming();
    testDirect();
    testVolume();
    std::printf("\n%d checks, %d failed\n", gChecks, gFail);
    return gFail ? 1 : 0;
}
