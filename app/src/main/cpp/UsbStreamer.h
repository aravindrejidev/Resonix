#pragma once
// Keeps an isochronous USB audio OUT endpoint fed: a thread that always has a few milliseconds of
// transfers queued, fills each one with audio just before it is sent, and (when the DAC runs its own
// clock) follows the DAC's feedback endpoint so the stream neither starves nor overflows it.

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <thread>
#include <vector>

#include "UsbTransport.h"

namespace resonix::usb {

// How many frames go into each packet. Without feedback that is the exact nominal rate
// (frames per packet = rate / packets-per-second, remainders carried over); with feedback it follows
// the device's reported rate in 16.16 fixed point.
class PacketPlanner {
public:
    // unitsPerSecond: 8000 microframes (high speed) or 1000 frames (full speed). periodUnits: how many
    // of those one packet spans (2^(bInterval-1) at high speed, 1 at full speed).
    PacketPlanner(int sampleRate, int unitsPerSecond, int periodUnits, int maxFramesPerPacket);

    int next();                          // frames for the next packet
    // Feedback: frames per *unit* (microframe / frame) as 16.16. Rejected (false) when it is further
    // than 12.5 % from nominal. [length] is the number of bytes the device sent: 3 = 10.14, 4 = 16.16.
    bool setFeedback(uint32_t value, int length);
    double feedbackRateHz() const;       // what the last accepted feedback means in frames per second (0 = none yet)

private:
    uint64_t mRate;
    uint64_t mPacketsPerSecond;
    uint64_t mAccumulator = 0;           // no feedback: remainder in 1/packetsPerSecond; feedback: 16.16 fraction
    uint32_t mNominalUnit16;
    uint32_t mFeedbackUnit16 = 0;
    int mPeriodUnits;
    int mUnitsPerSecond;
    int mMaxFrames;
};

class UsbStreamer {
public:
    struct Params {
        int sampleRate = 0;
        int frameBytes = 0;
        int endpoint = 0;
        int maxPacketBytes = 0;
        int interval = 1;              // bInterval of the data endpoint
        int feedbackEndpoint = 0;      // 0 = no explicit feedback
        int feedbackMaxPacketBytes = 4;
    };
    // Writes exactly [frames] frames into [dst], in the stream's sample format.
    using FillFn = std::function<void(uint8_t *dst, int frames)>;

    struct Stats {
        uint64_t framesSent = 0;
        uint64_t urbsCompleted = 0;
        uint64_t packetErrors = 0;
        double feedbackHz = 0.0;
    };

    explicit UsbStreamer(UsbTransport &transport);
    ~UsbStreamer();

    // Called once, from the streaming thread, when the stream dies (device gone / failing). Set before start().
    void setFailureCallback(std::function<void()> callback) { mOnFailed = std::move(callback); }

    bool start(const Params &params, FillFn fill);
    void stop();
    bool running() const { return mThread.joinable() && !mFailed.load(std::memory_order_relaxed); }
    // True once the device has gone away (or kept failing): the stream is dead.
    bool failed() const { return mFailed.load(std::memory_order_relaxed); }
    Stats stats() const;

    // Queue depth: kOutTransfers x about 4 ms.
    static constexpr int kOutTransfers = 12;
    static constexpr int kFeedbackTransfers = 4;

private:
    void threadMain();
    bool submitOut(IsoTransfer *t);
    bool submitFeedback(IsoTransfer *t);
    void handleFeedback(IsoTransfer *t);
    void fail();

    UsbTransport &mTransport;
    Params mParams;
    FillFn mFill;
    std::function<void()> mOnFailed;
    std::unique_ptr<PacketPlanner> mPlanner;
    std::vector<std::unique_ptr<IsoTransfer>> mOut;
    std::vector<std::unique_ptr<IsoTransfer>> mFeedback;
    std::thread mThread;
    std::atomic<bool> mStop{false};
    std::atomic<bool> mFailed{false};
    int mInFlight = 0;
    int mBadInARow = 0;

    std::atomic<uint64_t> mFramesSent{0};
    std::atomic<uint64_t> mUrbs{0};
    std::atomic<uint64_t> mPacketErrors{0};
    std::atomic<uint32_t> mFeedbackHzTimes16{0};
    int mUnitsPerSecond = 8000;
    int mPeriodUnits = 1;
};

}  // namespace resonix::usb
