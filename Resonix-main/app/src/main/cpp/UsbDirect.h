#pragma once
// A DAC driven directly by Resonix over USB, bypassing Android's audio stack (AudioFlinger) altogether:
// claims the audio interfaces, selects the sample rate and alt setting, and streams.

#include <functional>
#include <memory>
#include <string>

#include "UsbAudio.h"
#include "UsbStreamer.h"
#include "UsbTransport.h"

namespace resonix::usb {

class UsbDirect {
public:
    UsbDirect(std::unique_ptr<UsbTransport> transport, DeviceCaps caps, std::string name);
    ~UsbDirect();

    // The DAC's capabilities. For UAC2 the sample rates are only known after prepare(): asking the clock
    // needs the control interface, which means taking the DAC away from Android's driver.
    const DeviceCaps &caps() const { return mCaps; }

    // Claims what is needed to talk to the DAC and (UAC2) asks its clock for the rates it offers. Done
    // once, before the first song is played. False if the DAC cannot be claimed or offers no rate.
    bool prepare();
    const std::string &name() const { return mName; }

    struct OpenResult {
        const StreamFormat *format = nullptr;
        bool duplicateMono = false;
        int sampleRate = 0;
        int engineChannels = 0;
    };

    // Sets the DAC up for [rate] Hz / [channels] channels / samples of [sourceBits] bits (0 = unknown) and
    // starts streaming, pulling audio from [fill]. False if the DAC cannot play that or refuses.
    bool open(int rate, int channels, int sourceBits, UsbStreamer::FillFn fill, OpenResult *result);

    // True when the stream that is running already is the one [rate] / [channels] / [sourceBits] would
    // set up, so a new song can simply carry on through it.
    bool matches(int rate, int channels, int sourceBits) const;

    bool isOpen() const { return mOpen; }
    // The streaming thread gave up: the device is gone or kept failing.
    bool failed() const { return mStreamer && mStreamer->failed(); }
    const OpenResult &current() const { return mCurrent; }
    UsbStreamer::Stats stats() const { return mStreamer ? mStreamer->stats() : UsbStreamer::Stats{}; }

    // One line for the Signal card, e.g. "Direct USB · Topping D90 · 96 kHz · 24-bit · async (feedback)".
    std::string describeOpen() const;

    void close();               // stops streaming and switches the interface off; the DAC stays ours
    void releaseInterfaces();   // hands the DAC back to Android's own driver

    // Called (from the streaming thread) when the stream dies. Applies to streams opened afterwards.
    void setFailureCallback(std::function<void()> callback) { mOnFailed = std::move(callback); }

    // Time the DAC is given to settle after a rate change (ms). Tests shorten it.
    void setSettleMs(int ms) { mSettleMs = ms; }

private:
    int setClockRate(const StreamFormat &f, int rate);
    bool claim(int interfaceNumber);

    std::unique_ptr<UsbTransport> mTransport;
    DeviceCaps mCaps;
    std::string mName;
    std::unique_ptr<UsbStreamer> mStreamer;
    std::vector<int> mClaimed;
    bool mPrepared = false;
    bool mOpen = false;
    OpenResult mCurrent;
    int mSettleMs = 40;
    std::function<void()> mOnFailed;
};

}  // namespace resonix::usb
