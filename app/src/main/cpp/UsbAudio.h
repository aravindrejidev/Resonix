#pragma once
// USB Audio Class (1.0 and 2.0) descriptors: finding out what a DAC can play.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace resonix::usb {

class UsbTransport;

enum class SyncType : int { kNone = 0, kAsync = 1, kAdaptive = 2, kSync = 3 };

// One alternate setting of an AudioStreaming interface that plays PCM.
struct StreamFormat {
    int interfaceNumber = -1;
    int altSetting = 0;
    int uac = 1;                    // 1 or 2
    int channels = 0;
    int subslotBytes = 0;           // bytes per sample: 2, 3 or 4
    int bitResolution = 0;          // significant bits in it
    int terminalLink = 0;
    int clockId = 0;                // UAC2: the clock source that sets the sample rate
    std::vector<int> rates;         // sample rates this alt setting can play, ascending
    std::vector<int> rateRanges;    // UAC1 continuous: pairs {low, high}, expanded into [rates] later
    int endpoint = 0;               // isochronous OUT endpoint address
    int maxPacketBytes = 0;         // per (micro)frame, including extra transactions
    int interval = 1;               // bInterval of the data endpoint
    SyncType sync = SyncType::kAsync;
    int feedbackEndpoint = 0;       // isochronous IN endpoint address; 0 = none
    int feedbackMaxPacketBytes = 0;
    int feedbackInterval = 1;
    bool implicitFeedback = false;  // feedback comes from a capture endpoint: not supported

    int frameBytes() const { return channels * subslotBytes; }
};

struct DeviceCaps {
    int uac = 0;                    // 0 = no audio class found, else 1 or 2
    int controlInterface = -1;
    std::vector<StreamFormat> formats;  // playback alt settings that carry plain PCM
    bool hasCapture = false;            // an input (microphone) stream exists: Android then also wants the RECORD_AUDIO permission

    std::vector<int> rates() const;         // every rate any format can play, ascending
    int maxBitResolution() const;
    int maxChannels() const;
};

// Reads the configuration descriptor(s) as UsbDeviceConnection.getRawDescriptors() returns them
// (the 18-byte device descriptor first, then the configuration with everything inside it).
// False if there is no usable PCM playback stream in it.
bool parseDescriptors(const uint8_t *data, size_t length, DeviceCaps *caps);

// UAC2 only: ask the clock which sample rates it really offers (a GET_RANGE request) and fill
// every format's [rates]. UAC1 lists its rates in the descriptors already.
void queryClockRates(UsbTransport &transport, DeviceCaps *caps);

// Turns UAC1 "continuous" ranges into the standard rates inside them, then sorts and de-duplicates.
void finalizeRates(DeviceCaps *caps);

// The alt setting to play a track with: the sample rate exactly, the channel count (a mono file may go
// to a stereo alt, [duplicateMono] set), and the smallest sample size that still holds [sourceBits]
// (0 = unknown, then 24). Null if the rate or channel count cannot be played.
const StreamFormat *chooseFormat(const DeviceCaps &caps, int rate, int channels, int sourceBits,
                                 bool *duplicateMono);

// Of the device's rates the one to use for [wanted]: wanted itself, else the lowest multiple of it, else the
// next one up, else the highest below. 0 if the device lists none.
int nearestSupportedRate(const DeviceCaps &caps, int wanted);

std::string describe(const DeviceCaps &caps);

}  // namespace resonix::usb
