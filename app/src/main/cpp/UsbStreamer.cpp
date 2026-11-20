#include "UsbStreamer.h"

#include "UsbLog.h"
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>


namespace resonix::usb {
namespace {

constexpr int kFeedbackTagBase = 1000;
constexpr int kMaxUrbBytes = 16384;  // older kernels refuse bigger usbfs transfers
constexpr int kMaxPacketsPerUrb = 128;
constexpr int kTooManyBadInARow = 100;

// Audio deserves a thread that is not made to wait behind everything else. Best effort: an app is
// rarely allowed a real-time policy, but a better nice value is usually granted.
void raiseThreadPriority() {
    const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
    for (int nice : {-19, -16, -10}) {
        if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), nice) == 0) {
            USB_LOGI("USB audio thread priority %d", nice);
            return;
        }
    }
    USB_LOGW("USB audio thread keeps its normal priority (%s)", std::strerror(errno));
}

}  // namespace

// --- PacketPlanner -------------------------------------------------------------------------

PacketPlanner::PacketPlanner(int sampleRate, int unitsPerSecond, int periodUnits, int maxFramesPerPacket)
        : mRate(static_cast<uint64_t>(std::max(sampleRate, 1))),
          mPacketsPerSecond(static_cast<uint64_t>(std::max(unitsPerSecond / std::max(periodUnits, 1), 1))),
          mNominalUnit16(static_cast<uint32_t>((static_cast<uint64_t>(std::max(sampleRate, 1)) << 16) /
                                               static_cast<uint64_t>(std::max(unitsPerSecond, 1)))),
          mPeriodUnits(std::max(periodUnits, 1)),
          mUnitsPerSecond(std::max(unitsPerSecond, 1)),
          mMaxFrames(std::max(maxFramesPerPacket, 1)) {}

int PacketPlanner::next() {
    uint64_t frames;
    if (mFeedbackUnit16 == 0) {
        mAccumulator += mRate;
        frames = mAccumulator / mPacketsPerSecond;
        mAccumulator %= mPacketsPerSecond;
    } else {
        mAccumulator += static_cast<uint64_t>(mFeedbackUnit16) * static_cast<uint64_t>(mPeriodUnits);
        frames = mAccumulator >> 16;
        mAccumulator &= 0xFFFF;
    }
    return static_cast<int>(std::min<uint64_t>(frames, static_cast<uint64_t>(mMaxFrames)));
}

bool PacketPlanner::setFeedback(uint32_t value, int length) {
    if (value == 0) return false;
    // 3 bytes are 10.14, 4 bytes are 16.16. Devices are not always careful about which they mean, so
    // whichever reading lands near the nominal rate wins.
    const uint64_t first = (length == 3) ? (static_cast<uint64_t>(value) << 2) : value;
    const uint64_t candidates[3] = {first, first << 2, first >> 2};
    const uint64_t nominal = mNominalUnit16;
    for (uint64_t c : candidates) {
        const uint64_t slack = nominal / 8;
        if (c + slack >= nominal && c <= nominal + slack && c <= 0xFFFFFFFFull) {
            if (mFeedbackUnit16 == 0) mAccumulator = 0;  // switching from "exact" to 16.16: restart the fraction
            mFeedbackUnit16 = static_cast<uint32_t>(c);
            return true;
        }
    }
    return false;
}

double PacketPlanner::feedbackRateHz() const {
    if (mFeedbackUnit16 == 0) return 0.0;
    return static_cast<double>(mFeedbackUnit16) * mUnitsPerSecond / 65536.0;
}

// --- UsbStreamer ---------------------------------------------------------------------------

UsbStreamer::UsbStreamer(UsbTransport &transport) : mTransport(transport) {}

void UsbStreamer::fail() {
    if (!mFailed.exchange(true) && mOnFailed) mOnFailed();
}

UsbStreamer::~UsbStreamer() { stop(); }

bool UsbStreamer::start(const Params &params, FillFn fill) {
    stop();
    if (params.sampleRate <= 0 || params.frameBytes <= 0 || params.endpoint == 0 || params.maxPacketBytes < params.frameBytes ||
        !fill) {
        return false;
    }
    mParams = params;
    mFill = std::move(fill);
    mFailed.store(false);
    mStop.store(false);
    mInFlight = 0;
    mBadInARow = 0;
    mFramesSent = 0;
    mUrbs = 0;
    mPacketErrors = 0;
    mFeedbackHzTimes16 = 0;

    const int speed = mTransport.speed();
    mUnitsPerSecond = (speed >= 3) ? 8000 : 1000;
    mPeriodUnits = (speed >= 3) ? (1 << std::min(std::max(params.interval - 1, 0), 15)) : 1;
    const int packetsPerSecond = std::max(mUnitsPerSecond / mPeriodUnits, 1);
    mPlanner = std::make_unique<PacketPlanner>(params.sampleRate, mUnitsPerSecond, mPeriodUnits,
                                               params.maxPacketBytes / params.frameBytes);

    // About 4 ms per transfer, but within what usbfs accepts.
    const int limit = std::max(1, std::min(kMaxPacketsPerUrb, kMaxUrbBytes / params.maxPacketBytes));
    const int packetsPerUrb = std::max(1, std::min(packetsPerSecond / 250, limit));

    mOut.clear();
    for (int i = 0; i < kOutTransfers; i++) {
        auto t = std::make_unique<IsoTransfer>();
        t->endpoint = static_cast<uint8_t>(params.endpoint & 0x7F);
        t->buffer.assign(static_cast<size_t>(packetsPerUrb) * params.maxPacketBytes, 0);
        t->packets.assign(static_cast<size_t>(packetsPerUrb), IsoPacket{});
        t->tag = i;
        mOut.push_back(std::move(t));
    }
    mFeedback.clear();
    if (params.feedbackEndpoint != 0) {
        const int fbBytes = std::max(3, std::min(params.feedbackMaxPacketBytes, 4));
        for (int i = 0; i < kFeedbackTransfers; i++) {
            auto t = std::make_unique<IsoTransfer>();
            t->endpoint = static_cast<uint8_t>(params.feedbackEndpoint | 0x80);
            t->buffer.assign(static_cast<size_t>(fbBytes), 0);
            t->packets.assign(1, IsoPacket{});
            t->tag = kFeedbackTagBase + i;
            mFeedback.push_back(std::move(t));
        }
    }

    mThread = std::thread([this] { threadMain(); });
    return true;
}

void UsbStreamer::stop() {
    mStop.store(true);
    if (mThread.joinable()) mThread.join();
    mOut.clear();
    mFeedback.clear();
    mPlanner.reset();
}

UsbStreamer::Stats UsbStreamer::stats() const {
    Stats s;
    s.framesSent = mFramesSent.load();
    s.urbsCompleted = mUrbs.load();
    s.packetErrors = mPacketErrors.load();
    s.feedbackHz = mFeedbackHzTimes16.load() / 16.0;
    return s;
}

bool UsbStreamer::submitOut(IsoTransfer *t) {
    int total = 0;
    for (IsoPacket &p : t->packets) {
        const int frames = mPlanner->next();
        p.length = static_cast<uint32_t>(frames * mParams.frameBytes);
        p.actualLength = 0;
        p.status = 0;
        total += frames;
    }
    const size_t bytes = static_cast<size_t>(total) * mParams.frameBytes;
    t->buffer.resize(bytes);  // never grows past what was allocated: no allocation here
    if (total > 0) mFill(t->buffer.data(), total);
    t->status = 0;
    const int rc = mTransport.submit(t);
    if (rc != 0) {
        USB_LOGE("Submitting audio failed: %s", std::strerror(-rc));
        return false;
    }
    mInFlight++;
    mFramesSent.fetch_add(static_cast<uint64_t>(total), std::memory_order_relaxed);
    return true;
}

bool UsbStreamer::submitFeedback(IsoTransfer *t) {
    t->packets[0].length = static_cast<uint32_t>(t->buffer.size());
    t->packets[0].actualLength = 0;
    t->packets[0].status = 0;
    t->status = 0;
    const int rc = mTransport.submit(t);
    if (rc != 0) {
        USB_LOGW("Submitting a feedback read failed: %s", std::strerror(-rc));
        return false;
    }
    mInFlight++;
    return true;
}

void UsbStreamer::handleFeedback(IsoTransfer *t) {
    if (t->status == 0 && t->packets[0].status == 0) {
        const uint32_t len = t->packets[0].actualLength;
        if (len == 3 || len == 4) {
            uint32_t v = 0;
            for (uint32_t i = 0; i < len; i++) v |= static_cast<uint32_t>(t->buffer[i]) << (8 * i);
            if (mPlanner->setFeedback(v, static_cast<int>(len))) {
                mFeedbackHzTimes16.store(static_cast<uint32_t>(mPlanner->feedbackRateHz() * 16.0 + 0.5));
            }
        }
    }
    if (!mStop.load()) submitFeedback(t);
}

void UsbStreamer::threadMain() {
    raiseThreadPriority();

    bool ok = true;
    for (auto &t : mOut) ok = ok && submitOut(t.get());
    for (auto &t : mFeedback) submitFeedback(t.get());  // a missing feedback read is not fatal
    if (!ok) fail();

    while (ok && !mStop.load()) {
        int error = 0;
        IsoTransfer *t = mTransport.reap(100, &error);
        if (t == nullptr) {
            if (error < 0) {
                USB_LOGE("USB device lost while streaming: %s", std::strerror(-error));
                fail();
                break;
            }
            continue;  // timeout: look at the stop flag again
        }
        mInFlight--;
        if (t->tag >= kFeedbackTagBase) {
            handleFeedback(t);
            continue;
        }

        const uint64_t urbs = mUrbs.fetch_add(1, std::memory_order_relaxed) + 1;
        if (urbs % 2000 == 0) {  // about every 8 seconds: a line for the USB report
            USB_LOGI("stream: %llu frames, %llu transfers, %llu packet errors, feedback %.1f Hz",
                 static_cast<unsigned long long>(mFramesSent.load()), static_cast<unsigned long long>(urbs),
                 static_cast<unsigned long long>(mPacketErrors.load()), mFeedbackHzTimes16.load() / 16.0);
        }
        if (t->status == -ENODEV || t->status == -ESHUTDOWN) {
            USB_LOGE("USB device gone (%s)", std::strerror(-t->status));
            fail();
            break;
        }
        int bad = (t->status != 0) ? 1 : 0;
        for (const IsoPacket &p : t->packets) {
            if (p.status != 0) bad++;
        }
        if (bad > 0) {
            mPacketErrors.fetch_add(static_cast<uint64_t>(bad), std::memory_order_relaxed);
            if (++mBadInARow > kTooManyBadInARow) {
                USB_LOGE("USB audio keeps failing, giving up");
                fail();
                break;
            }
        } else {
            mBadInARow = 0;
        }
        if (!submitOut(t)) {
            fail();
            break;
        }
    }

    USB_LOGI("stream ended: %llu frames, %llu transfers, %llu packet errors, feedback %.1f Hz",
         static_cast<unsigned long long>(mFramesSent.load()), static_cast<unsigned long long>(mUrbs.load()),
         static_cast<unsigned long long>(mPacketErrors.load()), mFeedbackHzTimes16.load() / 16.0);
    // Take everything back from the kernel before the buffers go away.
    for (auto &t : mOut) mTransport.discard(t.get());
    for (auto &t : mFeedback) mTransport.discard(t.get());
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (mInFlight > 0 && std::chrono::steady_clock::now() < deadline) {
        int error = 0;
        IsoTransfer *t = mTransport.reap(50, &error);
        if (t != nullptr) {
            mInFlight--;
        } else if (error < 0) {
            break;  // the device is gone: nothing more will come back
        }
    }
}

}  // namespace resonix::usb
