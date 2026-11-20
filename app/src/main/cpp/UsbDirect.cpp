#include "UsbDirect.h"

#include "UsbLog.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <chrono>
#include <cstring>
#include <thread>


namespace resonix::usb {

UsbDirect::UsbDirect(std::unique_ptr<UsbTransport> transport, DeviceCaps caps, std::string name)
        : mTransport(std::move(transport)), mCaps(std::move(caps)), mName(std::move(name)) {
    refreshDetailText();
}

UsbDirect::~UsbDirect() {
    {
        std::lock_guard<std::mutex> lock(mVolumeMutex);
        mVolumeStop = true;
    }
    mVolumeCv.notify_all();
    if (mVolumeThread.joinable()) mVolumeThread.join();
    close();
    releaseInterfaces();
}

void UsbDirect::refreshDetailText() {
    std::string text = describeDetailed(mCaps);
    std::lock_guard<std::mutex> lock(mInfoMutex);
    mDetailText = std::move(text);
}

std::string UsbDirect::detailText() const {
    std::lock_guard<std::mutex> lock(mInfoMutex);
    return mName + "\n" + mDetailText;
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
                USB_LOGW("DAC clock says %u Hz after being set to %d Hz", now, rate);
                return -EIO;
            }
        }  // a DAC that cannot answer the read-back is trusted
        return 0;
    }
    // UAC1: the rate belongs to the endpoint, set after the alt setting is selected.
    uint8_t data[3] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8), static_cast<uint8_t>(rate >> 16)};
    const int rc = mTransport->control(0x22, 0x01, 0x0100, static_cast<uint16_t>(f.endpoint), data, 3, 500);
    if (rc < 0) USB_LOGW("UAC1 sample rate request failed (%s) — carrying on", std::strerror(-rc));
    return 0;  // many UAC1 DACs have a fixed or automatic rate and stall this: not an error
}

bool UsbDirect::prepare() {
    std::lock_guard<std::mutex> control(mControlMutex);
    if (mPrepared) return true;
    if (mCaps.formats.empty()) return false;
    const bool uac2 = mCaps.formats[0].uac == 2;
    // The control interface is needed for the clock (UAC2) and for the volume control; claiming it takes the
    // DAC away from Android's driver.
    if ((uac2 || mCaps.volume.present) && !claim(mCaps.controlInterface)) {
        if (uac2) return false;
        mCaps.volume.present = false;  // cannot reach the volume control: play without it
    }
    if (uac2) {
        queryClockRates(*mTransport, &mCaps);
        mCaps.formats.erase(std::remove_if(mCaps.formats.begin(), mCaps.formats.end(),
                                           [](const StreamFormat &f) { return f.rates.empty(); }),
                            mCaps.formats.end());
        if (mCaps.formats.empty()) {
            USB_LOGW("The DAC's clock offered no sample rate");
            for (int n : mClaimed) mTransport->releaseInterface(n);
            mClaimed.clear();
            refreshDetailText();
            return false;
        }
    }
    if (mCaps.volume.present) {
        queryVolumeRange(*mTransport, &mCaps, mCaps.controlInterface);
        if (mCaps.volume.rangeKnown) {
            USB_LOGI("DAC volume control: %.1f .. %.1f dB, step %.2f dB, now %.1f dB", mCaps.volume.min256 / 256.0,
                 mCaps.volume.max256 / 256.0, mCaps.volume.res256 / 256.0, mCaps.volume.original256 / 256.0);
        } else {
            USB_LOGW("The DAC has a volume control but did not report its range: not used");
        }
    }
    refreshDetailText();
    mPrepared = true;
    return true;
}

int UsbDirect::planVolume256(double gainDb, double *residualDb) const {
    const VolumeControl &v = mCaps.volume;
    const double ref = std::min<double>(std::max<double>(0.0, v.min256), v.max256);  // "unity": 0 dB if reachable
    double target = ref + gainDb * 256.0;
    target = std::min<double>(std::max<double>(target, v.min256), v.max256);
    const double steps = std::round((target - v.min256) / v.res256);
    int applied = v.min256 + static_cast<int>(steps) * v.res256;
    applied = std::min(std::max(applied, v.min256), v.max256);
    if (residualDb != nullptr) *residualDb = (gainDb * 256.0 - (applied - ref)) / 256.0;
    return applied;
}

bool UsbDirect::sendVolume(int value256) {
    const VolumeControl &v = mCaps.volume;
    const uint16_t index = static_cast<uint16_t>((v.unitId << 8) | (mCaps.controlInterface & 0xFF));
    bool ok = false;
    for (int ch : v.channels) {
        uint8_t data[2] = {static_cast<uint8_t>(value256 & 0xFF), static_cast<uint8_t>((value256 >> 8) & 0xFF)};
        const int rc = mTransport->control(0x21, 0x01, static_cast<uint16_t>((0x02 << 8) | ch), index, data, 2, 300);
        if (rc == 2) {
            ok = true;
        } else {
            USB_LOGW("Setting the DAC volume (channel %d) failed: %s", ch, rc < 0 ? std::strerror(-rc) : "short transfer");
        }
    }
    return ok;
}

bool UsbDirect::setHardwareVolumeBlocking(double gainDb, double *residualDb) {
    std::lock_guard<std::mutex> control(mControlMutex);
    if (!mCaps.volume.usable()) return false;
    const int applied = planVolume256(gainDb, residualDb);
    if (!sendVolume(applied)) return false;
    mVolumeApplied256 = applied;
    return true;
}

void UsbDirect::requestHardwareVolume(double gainDb) {
    std::lock_guard<std::mutex> lock(mVolumeMutex);
    if (mVolumeStop) return;
    mVolumePending = gainDb;
    mVolumeHave = true;
    if (!mVolumeThread.joinable()) mVolumeThread = std::thread([this] { volumeLoop(); });
    mVolumeCv.notify_one();
}

void UsbDirect::volumeLoop() {
    std::unique_lock<std::mutex> lock(mVolumeMutex);
    for (;;) {
        mVolumeCv.wait(lock, [this] { return mVolumeHave || mVolumeStop; });
        if (mVolumeStop) return;
        const double gainDb = mVolumePending;
        mVolumeHave = false;
        lock.unlock();
        {
            std::lock_guard<std::mutex> control(mControlMutex);
            if (mCaps.volume.usable()) {
                const int applied = planVolume256(gainDb, nullptr);
                if (applied != mVolumeApplied256 && sendVolume(applied)) mVolumeApplied256 = applied;
            }
        }
        lock.lock();
    }
}

void UsbDirect::restoreVolume() {
    {
        std::lock_guard<std::mutex> lock(mVolumeMutex);
        mVolumeHave = false;  // nothing queued may undo this
    }
    std::lock_guard<std::mutex> control(mControlMutex);
    if (mCaps.volume.usable() && mCaps.volume.haveOriginal && mVolumeApplied256 != 0x7FFFFFFF) {
        if (sendVolume(mCaps.volume.original256)) USB_LOGI("DAC volume put back to %.1f dB", mCaps.volume.original256 / 256.0);
        mVolumeApplied256 = 0x7FFFFFFF;
    }
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
        USB_LOGW("Asynchronous endpoint without feedback: the stream will run at the nominal rate");
    }

    {
        std::lock_guard<std::mutex> control(mControlMutex);
        if (f->uac == 2 && !claim(mCaps.controlInterface)) return false;
        if (!claim(f->interfaceNumber)) return false;

        // Off, set the clock, then on: the order DACs expect.
        mTransport->setAltSetting(f->interfaceNumber, 0);
        if (f->uac == 2) {
            const int rc = setClockRate(*f, rate);
            if (rc < 0) {
                USB_LOGE("Setting the DAC to %d Hz failed: %s", rate, std::strerror(-rc));
                return false;
            }
        }
        if (mTransport->setAltSetting(f->interfaceNumber, f->altSetting) != 0) return false;
        if (f->uac == 1) setClockRate(*f, rate);
        if (mSettleMs > 0) std::this_thread::sleep_for(std::chrono::milliseconds(mSettleMs));
    }

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
    USB_LOGI("Direct USB: %s", describeOpen().c_str());
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
        std::lock_guard<std::mutex> control(mControlMutex);
        mTransport->setAltSetting(mCurrent.format->interfaceNumber, 0);  // stop the DAC's stream cleanly
    }
    mOpen = false;
    mCurrent = OpenResult{};
}

void UsbDirect::releaseInterfaces() {
    restoreVolume();  // leave the DAC's volume where it was, so Android's own use of it is not surprised
    std::lock_guard<std::mutex> control(mControlMutex);
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
