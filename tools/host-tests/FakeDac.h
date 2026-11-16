// A simulated USB DAC for the host tests: descriptor blobs for a UAC2 (high speed, asynchronous with
// a feedback endpoint) and a UAC1 (full speed, adaptive) device, and a UsbTransport that behaves like
// one — control requests, alt settings, isochronous transfers that "complete" in virtual time (run
// [speedup] times faster than real time) and record every byte the host sent.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <deque>
#include <cstdio>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "UsbTransport.h"

namespace fake {

using Bytes = std::vector<uint8_t>;

inline void put16(Bytes &b, int v) { b.push_back(v & 0xFF); b.push_back((v >> 8) & 0xFF); }
inline void put24(Bytes &b, int v) { put16(b, v); b.push_back((v >> 16) & 0xFF); }
inline void put32(Bytes &b, uint32_t v) { put16(b, v & 0xFFFF); put16(b, v >> 16); }
inline void append(Bytes &to, const Bytes &from) { to.insert(to.end(), from.begin(), from.end()); }

inline Bytes iface(int num, int alt, int endpoints, int cls, int sub, int proto) {
    return {9, 4, (uint8_t)num, (uint8_t)alt, (uint8_t)endpoints, (uint8_t)cls, (uint8_t)sub, (uint8_t)proto, 0};
}
inline Bytes endpoint(int addr, int attr, int maxPacket, int interval, bool uac1 = false, int refresh = 0, int synch = 0) {
    Bytes b = {(uint8_t)(uac1 ? 9 : 7), 5, (uint8_t)addr, (uint8_t)attr};
    put16(b, maxPacket);
    b.push_back(interval);
    if (uac1) { b.push_back(refresh); b.push_back(synch); }
    return b;
}

struct Uac2Alt { int alt; int subslot; int bits; bool feedback = true; int syncAttr = 0x05; };

// UAC2: control interface 0, clock source 1 (internal), input terminal 2 (USB streaming), output terminal 3;
// streaming interface 1 with the given alt settings (OUT 0x01, feedback IN 0x81).
inline Bytes uac2Descriptors(const std::vector<Uac2Alt> &alts, int channels = 2, bool withCapture = false,
                              int maxPacket = 400) {
    Bytes dev = {18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 0, 0, 0, 1};
    Bytes body;
    append(body, iface(0, 0, 0, 1, 1, 0x20));
    {   // class-specific AC
        Bytes hdr = {9, 0x24, 0x01, 0x00, 0x02, 0x01, 0, 0, 0}; append(body, hdr);
        Bytes clk = {8, 0x24, 0x0A, 1, 1, 7, 0, 0}; append(body, clk);
        Bytes it = {17, 0x24, 0x02, 2}; put16(it, 0x0101); it.push_back(0); it.push_back(1); it.push_back(channels);
        put32(it, 3); it.push_back(0); put16(it, 0); it.push_back(0); append(body, it);
        Bytes ot = {12, 0x24, 0x03, 3}; put16(ot, 0x0301); ot.push_back(0); ot.push_back(2); ot.push_back(1); put16(ot, 0); ot.push_back(0); append(body, ot);
    }
    append(body, iface(1, 0, 0, 1, 2, 0x20));
    for (const Uac2Alt &a : alts) {
        append(body, iface(1, a.alt, a.feedback ? 2 : 1, 1, 2, 0x20));
        Bytes g = {16, 0x24, 0x01, 2, 0, 1}; put32(g, 1); g.push_back(channels); put32(g, 3); g.push_back(0); append(body, g);
        Bytes f = {6, 0x24, 0x02, 1, (uint8_t)a.subslot, (uint8_t)a.bits}; append(body, f);
        append(body, endpoint(0x01, a.syncAttr, maxPacket, 1));
        Bytes cs = {8, 0x25, 0x01, 0, 0, 0}; put16(cs, 0); append(body, cs);
        if (a.feedback) append(body, endpoint(0x81, 0x11, 4, 4));
    }
    if (withCapture) {
        append(body, iface(2, 1, 1, 1, 2, 0x20));
        Bytes g = {16, 0x24, 0x01, 4, 0, 1}; put32(g, 1); g.push_back(2); put32(g, 3); g.push_back(0); append(body, g);
        Bytes f = {6, 0x24, 0x02, 1, 2, 16}; append(body, f);
        append(body, endpoint(0x82, 0x25, 200, 1));
    }
    Bytes cfg = {9, 2}; put16(cfg, 9 + (int)body.size()); cfg.push_back(3); cfg.push_back(1); cfg.push_back(0); cfg.push_back(0x80); cfg.push_back(50);
    Bytes all = dev; append(all, cfg); append(all, body);
    return all;
}

struct Uac1Alt { int alt; int channels; int subframe; int bits; std::vector<int> rates; bool async = false; };

// UAC1 (full speed): control interface 0, streaming interface 1. Adaptive OUT 0x01, or async with sync IN 0x81.
inline Bytes uac1Descriptors(const std::vector<Uac1Alt> &alts) {
    Bytes dev = {18, 1, 0x10, 0x01, 0, 0, 0, 64, 0x34, 0x12, 0x79, 0x56, 0x00, 0x01, 0, 0, 0, 1};
    Bytes body;
    append(body, iface(0, 0, 0, 1, 1, 0));
    Bytes hdr = {9, 0x24, 0x01, 0x00, 0x01, 0, 0, 1, 1}; append(body, hdr);
    Bytes it = {12, 0x24, 0x02, 1}; put16(it, 0x0101); it.push_back(0); it.push_back(2); put16(it, 3); it.push_back(0); it.push_back(0); append(body, it);
    Bytes ot = {9, 0x24, 0x03, 2}; put16(ot, 0x0301); ot.push_back(0); ot.push_back(1); ot.push_back(0); append(body, ot);
    append(body, iface(1, 0, 0, 1, 2, 0));
    for (const Uac1Alt &a : alts) {
        append(body, iface(1, a.alt, a.async ? 2 : 1, 1, 2, 0));
        Bytes g = {7, 0x24, 0x01, 1, 1}; put16(g, 1); append(body, g);
        Bytes f = {(uint8_t)(8 + 3 * (a.rates.empty() ? 2 : (int)a.rates.size())), 0x24, 0x02, 1, (uint8_t)a.channels, (uint8_t)a.subframe, (uint8_t)a.bits,
                   (uint8_t)(a.rates.empty() ? 0 : a.rates.size())};
        if (a.rates.empty()) { put24(f, 8000); put24(f, 48000); }
        for (int r : a.rates) put24(f, r);
        append(body, f);
        append(body, endpoint(0x01, a.async ? 0x05 : 0x09, 300, 1, true, 0, a.async ? 0x81 : 0));
        Bytes cs = {7, 0x25, 0x01, 0x01, 0, 0, 0}; append(body, cs);
        if (a.async) append(body, endpoint(0x81, 0x01, 3, 1, true, 5, 0));
    }
    Bytes cfg = {9, 2}; put16(cfg, 9 + (int)body.size()); cfg.push_back(2); cfg.push_back(1); cfg.push_back(0); cfg.push_back(0x80); cfg.push_back(50);
    Bytes all = dev; append(all, cfg); append(all, body);
    return all;
}

// ------------------------------------------------------------------------------------------------

class FakeDac : public resonix::usb::UsbTransport {
public:
    using Clock = std::chrono::steady_clock;

    int speedValue = 3;              // 3 = high speed, 2 = full speed
    double deviceRateHz = 44100.0;   // the DAC's own clock, for the feedback it reports
    int feedbackBytes = 4;           // 4 = 16.16 per microframe, 3 = 10.14 per frame
    int feedbackIntervalUnits = 32;  // how often a feedback transfer completes, in (micro)frames
    double speedup = 16.0;           // virtual time runs this much faster than real time
    std::vector<int> clockRates = {44100, 48000, 88200, 96000, 176400, 192000};  // what GET_RANGE returns
    bool rejectClockSet = false;
    int clockClaimRate = 0;          // what GET_CUR claims (0 = whatever was set)

    // observations
    std::mutex mu;
    std::vector<uint8_t> received;              // every audio byte sent, in order
    std::vector<uint32_t> packetLengths;        // length of every packet, in order
    std::map<int, int> altOf;                   // interface -> current alt
    std::vector<int> claimedNow;
    std::vector<int> releasedLog;
    int uac2Rate = 0;
    int uac1Rate = 0;
    uint64_t underruns = 0;                     // transfers that were submitted too late
    std::vector<std::pair<int,int>> altLog;     // (interface, alt) in order
    std::vector<std::string> controlLog;
    std::atomic<bool> unplugged{false};
    int maxSeenInFlight = 0;

    int claimInterface(int n) override { std::lock_guard<std::mutex> l(mu); claimedNow.push_back(n); return 0; }
    void releaseInterface(int n) override {
        std::lock_guard<std::mutex> l(mu);
        for (size_t i = 0; i < claimedNow.size(); i++) if (claimedNow[i] == n) { claimedNow.erase(claimedNow.begin() + i); break; }
        releasedLog.push_back(n);
    }
    int setAltSetting(int n, int alt) override { std::lock_guard<std::mutex> l(mu); altOf[n] = alt; altLog.push_back({n, alt}); return 0; }
    int speed() override { return speedValue; }

    int control(uint8_t rt, uint8_t req, uint16_t value, uint16_t index, void *data, uint16_t length, unsigned) override {
        std::lock_guard<std::mutex> l(mu);
        char buf[96];
        std::snprintf(buf, sizeof(buf), "%02X %02X %04X %04X len=%u", rt, req, value, index, length);
        controlLog.push_back(std::string(buf));
        auto *d = static_cast<uint8_t *>(data);
        if (rt == 0xA1 && req == 0x02 && value == 0x0100) {          // UAC2 clock GET_RANGE
            Bytes r; put16(r, (int)clockRates.size());
            for (int rate : clockRates) { put32(r, rate); put32(r, rate); put32(r, 0); }
            const int n = std::min<int>(length, (int)r.size());
            std::memcpy(d, r.data(), n);
            return n;
        }
        if (rt == 0x21 && req == 0x01 && value == 0x0100 && length == 4) {   // UAC2 SET_CUR
            if (rejectClockSet) return -32;
            uac2Rate = d[0] | (d[1] << 8) | (d[2] << 16) | (d[3] << 24);
            return 4;
        }
        if (rt == 0xA1 && req == 0x01 && value == 0x0100 && length == 4) {   // UAC2 GET_CUR
            const int v = clockClaimRate ? clockClaimRate : uac2Rate;
            d[0] = v; d[1] = v >> 8; d[2] = v >> 16; d[3] = v >> 24;
            return 4;
        }
        if (rt == 0x22 && req == 0x01 && length == 3) {                       // UAC1 endpoint SET_CUR
            uac1Rate = d[0] | (d[1] << 8) | (d[2] << 16);
            return 3;
        }
        return -32;  // EPIPE: stalled
    }

    int submit(resonix::usb::IsoTransfer *t) override {
        std::lock_guard<std::mutex> l(mu);
        if (unplugged) return -19;
        const double unitSec = speedValue >= 3 ? 1.0 / 8000 : 1.0 / 1000;
        if (!started.load(std::memory_order_relaxed)) { t0 = Clock::now(); started.store(true, std::memory_order_release); }
        const double now = virtualNow();
        double duration = 0;
        const bool in = (t->endpoint & 0x80) != 0;
        if (in) {
            // a feedback read: completes one feedback period after the previous one
            const double start = std::max(now, nextFeedback);
            duration = feedbackIntervalUnits * unitSec;
            nextFeedback = start + duration;
            pending.push_back({start + duration, t, true});
        } else {
            double start = nextOut;
            if (start < now) { if (outStarted) underruns++; start = now; }   // the queue ran dry
            outStarted = true;
            duration = t->packets.size() * unitSec;   // interval 1: one packet per (micro)frame
            nextOut = start + duration;
            pending.push_back({start + duration, t, false});
        }
        maxSeenInFlight = std::max(maxSeenInFlight, (int)pending.size());
        return 0;
    }

    resonix::usb::IsoTransfer *reap(int timeoutMs, int *error) override {
        *error = 0;
        const auto limit = Clock::now() + std::chrono::milliseconds(timeoutMs);
        for (;;) {
            {
                std::lock_guard<std::mutex> l(mu);
                if (unplugged) { *error = -19; return nullptr; }
                auto it = pending.end();
                for (auto i = pending.begin(); i != pending.end(); ++i) {
                    if (i->discarded) { it = i; break; }
                }
                if (it == pending.end()) {
                    for (auto i = pending.begin(); i != pending.end(); ++i) {
                        if (it == pending.end() || i->done < it->done) it = i;
                    }
                    if (it != pending.end() && Clock::now() < realTime(it->done)) it = pending.end();
                }
                if (it != pending.end()) {
                    const Pending p = *it;
                    pending.erase(it);
                    return completeLocked(p);
                }
            }
            if (Clock::now() >= limit) return nullptr;
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    }

    void discard(resonix::usb::IsoTransfer *t) override {
        std::lock_guard<std::mutex> l(mu);
        for (auto &p : pending) if (p.t == t) p.discarded = true;
    }

    // frames per second the host actually sent, measured over the packets seen so far
    double sentFrames(int frameBytes) { std::lock_guard<std::mutex> l(mu); return received.size() / (double)frameBytes; }
    double virtualNow() const { return started.load(std::memory_order_acquire) ? std::chrono::duration<double>(Clock::now() - t0).count() * speedup : 0.0; }
    // Seconds of device time the packets sent so far occupied (one per (micro)frame) — independent of how late
    // the host was, so a slow machine does not change what is being measured. Read after the stream has stopped.
    double packetSeconds() const { return packetLengths.size() / (speedValue >= 3 ? 8000.0 : 1000.0); }

private:
    struct Pending { double done; resonix::usb::IsoTransfer *t; bool feedback; bool discarded = false; };
    std::vector<Pending> pending;
    std::atomic<bool> started{false};
    bool outStarted = false;
    Clock::time_point t0;
    double nextOut = 0, nextFeedback = 0, lastOutEnd = 0;

    Clock::time_point realTime(double v) const { return t0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(v / speedup)); }

    resonix::usb::IsoTransfer *completeLocked(const Pending &p) {
        using resonix::usb::IsoTransfer;
        IsoTransfer *t = p.t;
        if (p.discarded) { t->status = -2; return t; }
        t->status = 0;
        if (p.feedback) {
            const double perUnit = deviceRateHz / (speedValue >= 3 ? 8000.0 : 1000.0);
            if (feedbackBytes == 4) {
                const uint32_t v = (uint32_t)(perUnit * 65536.0 + 0.5);
                for (int i = 0; i < 4; i++) t->buffer[i] = (v >> (8 * i)) & 0xFF;
                t->packets[0].actualLength = 4;
            } else {
                const uint32_t v = (uint32_t)(perUnit * 16384.0 + 0.5);
                for (int i = 0; i < 3; i++) t->buffer[i] = (v >> (8 * i)) & 0xFF;
                t->packets[0].actualLength = 3;
            }
            t->packets[0].status = 0;
        } else {
            size_t off = 0;
            for (auto &pk : t->packets) {
                pk.actualLength = pk.length;
                pk.status = 0;
                received.insert(received.end(), t->buffer.begin() + off, t->buffer.begin() + off + pk.length);
                packetLengths.push_back(pk.length);
                off += pk.length;
            }
            lastOutEnd = p.done;
        }
        return t;
    }
};

}  // namespace fake
