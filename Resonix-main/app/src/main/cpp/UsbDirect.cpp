#include "UsbDirect.h"

#include <android/log.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <thread>

#define LOG_TAG "ResonixUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace resonix::usb {

UsbDirect::UsbDirect(std::unique_ptr<UsbTransport> transport, DeviceCaps caps, std::string name)
        : mTransport(std::move(transport)), mCaps(std::move(caps)), mName(std::move(name)) {}

UsbDirect::~UsbDirect() {
    close();
    releaseInterfaces();
}

bool UsbDirect::claim(int interfaceNumber) {
    if (interfaceNumber < 0) return false;
    if (std::find(mClaimed.begin(), mClaimed.end(), interfaceNumber) != mClaimed.end()) return true;
    if (mTransport->claimInterface(interfaceNumber) != 0) return false;
    mClaimed.push_back(interfaceNumber);
    return true;
}

int UsbDirect::setClockRate(const StreamFormat &f, int rate) {
    if (f.uac == 2) {
        // UAC2: SET_CUR on the clock source's sampling-frequency control, then read it back.
        if (mCaps.controlInterface < 0) return -EINVAL;
        const uint16_t index = static_cast<uint16_t>((f.clockId << 8) | (mCaps.controlInterface & 0xFF));
        uint8_t data[4] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8), static_cast<uint8_t>(rate >> 16),
                           static_cast<uint8_t>(rate >> 24)};
        int rc = mTransport->control(0x21, 0x01, 0x0100, index, data, 4, 500);
        if (rc < 0) return rc;
        uint8_t cur[4] = {};
        rc = mTransport->control(0xA1, 0x01, 0x0100, index, cur, 4, 500);
        if (rc == 4) {
            const uint32_t now = static_cast<uint32_t>(cur[0]) | (static_cast<uint32_t>(cur[1]) << 8) |
                                 (static_cast<uint32_t>(cur[2]) << 16) | (static_cast<uint32_t>(cur[3]) << 24);
            if (now != static_cast<uint32_t>(rate)) {
                LOGW("DAC clock says %u Hz after being set to %d Hz", now, rate);
                return -EIO;
            }
        }  // a DAC that cannot answer the read-back is trusted
        return 0;
    }
    // UAC1: the rate belongs to the endpoint, set after the alt setting is selected.
    uint8_t data[3] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8), static_cast<uint8_t>(rate >> 16)};
    const int rc = mTransport->control(0x22, 0x01, 0x0100, static_cast<uint16_t>(f.endpoint), data, 3, 500);
    if (rc < 0) LOGW("UAC1 sample rate request failed (%s) — carrying on", std::strerror(-rc));
    return 0;  // many UAC1 DACs have a fixed or automatic rate and stall this: not an error
}

bool UsbDirect::prepare() {
    if (mPrepared) return true;
    if (mCaps.formats.empty()) return false;
    if (mCaps.formats[0].uac == 2) {
        if (!claim(mCaps.controlInterface)) return false;
        queryClockRates(*mTransport, &mCaps);
        mCaps.formats.erase(std::remove_if(mCaps.formats.begin(), mCaps.formats.end(),
                                           [](const StreamFormat &f) { return f.rates.empty(); }),
                            mCaps.formats.end());
        if (mCaps.formats.empty()) {
            LOGW("The DAC's clock offered no sample rate");
            releaseInterfaces();
            return false;
        }
    }
    mPrepared = true;
    return true;
}

bool UsbDirect::matches(int rate, int channels, int sourceBits) const {
    if (!mOpen) return false;
    bool dup = false;
    const StreamFormat *f = chooseFormat(mCaps, rate, channels, sourceBits, &dup);
    return f == mCurrent.format && dup == mCurrent.duplicateMono && rate == mCurrent.sampleRate &&
           channels == mCurrent.engineChannels;
}

bool UsbDirect::open(int rate, int channels, int sourceBits, UsbStreamer::FillFn fill, OpenResult *result) {
    close();
    if (!prepare()) return false;
    bool dup = false;
    const StreamFormat *f = chooseFormat(mCaps, rate, channels, sourceBits, &dup);
    if (f == nullptr) return false;
    if (f->feedbackEndpoint == 0 && f->sync == SyncType::kAsync) {
        LOGW("Asynchronous endpoint without feedback: the stream will run at the nominal rate");
    }

    if (f->uac == 2 && !claim(mCaps.controlInterface)) return false;
    if (!claim(f->interfaceNumber)) return false;

    // Off, set the clock, then on: the order DACs expect.
    mTransport->setAltSetting(f->interfaceNumber, 0);
    if (f->uac == 2) {
        const int rc = setClockRate(*f, rate);
        if (rc < 0) {
            LOGE("Setting the DAC to %d Hz failed: %s", rate, std::strerror(-rc));
            return false;
        }
    }
    if (mTransport->setAltSetting(f->interfaceNumber, f->altSetting) != 0) return false;
    if (f->uac == 1) setClockRate(*f, rate);
    if (mSettleMs > 0) std::this_thread::sleep_for(std::chrono::milliseconds(mSettleMs));

    UsbStreamer::Params params;
    params.sampleRate = rate;
    params.frameBytes = f->frameBytes();
    params.endpoint = f->endpoint;
    params.maxPacketBytes = f->maxPacketBytes;
    params.interval = f->interval;
    params.feedbackEndpoint = (f->sync == SyncType::kAsync) ? f->feedbackEndpoint : 0;
    params.feedbackMaxPacketBytes = f->feedbackMaxPacketBytes > 0 ? f->feedbackMaxPacketBytes : 4;

    mStreamer = std::make_unique<UsbStreamer>(*mTransport);
    mStreamer->setFailureCallback(mOnFailed);
    if (!mStreamer->start(params, std::move(fill))) {
        mStreamer.reset();
        mTransport->setAltSetting(f->interfaceNumber, 0);
        return false;
    }
    mOpen = true;
    mCurrent.format = f;
    mCurrent.duplicateMono = dup;
    mCurrent.sampleRate = rate;
    mCurrent.engineChannels = channels;
    if (result != nullptr) *result = mCurrent;
    LOGI("Direct USB: %s", describeOpen().c_str());
    return true;
}

std::string UsbDirect::describeOpen() const {
    if (!mOpen || mCurrent.format == nullptr) return "";
    const StreamFormat &f = *mCurrent.format;
    char rate[32];
    if (mCurrent.sampleRate % 1000 == 0) {
        std::snprintf(rate, sizeof(rate), "%d kHz", mCurrent.sampleRate / 1000);
    } else {
        std::snprintf(rate, sizeof(rate), "%.1f kHz", mCurrent.sampleRate / 1000.0);
    }
    std::string text = "Direct USB · " + mName + " · " + rate + " · " + std::to_string(f.bitResolution) + "-bit";
    if (f.subslotBytes * 8 != f.bitResolution) text += " in " + std::to_string(f.subslotBytes * 8);
    switch (f.sync) {
        case SyncType::kAdaptive: text += " · adaptive"; break;
        case SyncType::kSync: text += " · synchronous"; break;
        default: text += f.feedbackEndpoint != 0 ? " · async (feedback)" : " · async"; break;
    }
    return text;
}

void UsbDirect::close() {
    if (mStreamer) {
        mStreamer->stop();
        mStreamer.reset();
    }
    if (mOpen && mCurrent.format != nullptr) {
        mTransport->setAltSetting(mCurrent.format->interfaceNumber, 0);  // stop the DAC's stream cleanly
    }
    mOpen = false;
    mCurrent = OpenResult{};
}

void UsbDirect::releaseInterfaces() {
    mPrepared = false;  // the next song claims the DAC and asks its clock again
    // Streaming interfaces first and the control interface last: the kernel's audio driver binds through
    // the control interface and takes the others along.
    std::vector<int> order;
    for (int n : mClaimed) {
        if (n != mCaps.controlInterface) order.push_back(n);
    }
    if (std::find(mClaimed.begin(), mClaimed.end(), mCaps.controlInterface) != mClaimed.end()) {
        order.push_back(mCaps.controlInterface);
    }
    for (int n : order) mTransport->releaseInterface(n);
    mClaimed.clear();
}

}  // namespace resonix::usb
