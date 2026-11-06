#ifndef RESONIX_EQ_H
#define RESONIX_EQ_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <mutex>

namespace resonix {

// Filter types. The values are shared with the Kotlin side (PeqType.nativeValue).
enum EqType : int {
    kEqPeak = 0,       // bell: boost or cut around a centre frequency
    kEqLowShelf = 1,   // boost or cut everything below the corner
    kEqHighShelf = 2,  // boost or cut everything above the corner
    kEqLowPass = 3,    // remove everything above
    kEqHighPass = 4,   // remove everything below
    kEqNotch = 5,      // remove a narrow band
};

struct EqBandParams {
    int type = kEqPeak;
    double freqHz = 1000.0;
    double gainDb = 0.0;  // ignored by low-pass / high-pass / notch
    double q = 1.0;
    bool enabled = true;
};

struct BiquadCoeffs {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
};

// RBJ "Audio EQ Cookbook" designs, Q form for shelves too (Q = 0.7071 is the maximally flat shelf,
// which is what Equalizer APO / AutoEQ / Peace call a plain LS / HS filter).
inline BiquadCoeffs designBiquad(int type, double freqHz, double gainDb, double q, double sampleRate) {
    const double fs = sampleRate > 0.0 ? sampleRate : 48000.0;
    const double f = std::min(std::max(freqHz, 10.0), 0.49 * fs);
    const double Q = std::min(std::max(q, 0.05), 50.0);
    const double w0 = 2.0 * 3.14159265358979323846 * f / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * Q);
    const double A = std::pow(10.0, std::min(std::max(gainDb, -40.0), 40.0) / 40.0);

    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
        case kEqLowShelf: {
            const double s = 2.0 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + s);
            b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - s);
            a0 = (A + 1) + (A - 1) * cw + s;
            a1 = -2 * ((A - 1) + (A + 1) * cw);
            a2 = (A + 1) + (A - 1) * cw - s;
            break;
        }
        case kEqHighShelf: {
            const double s = 2.0 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + s);
            b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - s);
            a0 = (A + 1) - (A - 1) * cw + s;
            a1 = 2 * ((A - 1) - (A + 1) * cw);
            a2 = (A + 1) - (A - 1) * cw - s;
            break;
        }
        case kEqLowPass:
            b0 = (1 - cw) / 2;
            b1 = 1 - cw;
            b2 = (1 - cw) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            break;
        case kEqHighPass:
            b0 = (1 + cw) / 2;
            b1 = -(1 + cw);
            b2 = (1 + cw) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            break;
        case kEqNotch:
            b0 = 1;
            b1 = -2 * cw;
            b2 = 1;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            break;
        case kEqPeak:
        default:
            b0 = 1 + alpha * A;
            b1 = -2 * cw;
            b2 = 1 - alpha * A;
            a0 = 1 + alpha / A;
            a1 = -2 * cw;
            a2 = 1 - alpha / A;
            break;
    }
    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

// |H(e^jw)| in dB at [freqHz].
inline double biquadMagnitudeDb(const BiquadCoeffs &c, double freqHz, double sampleRate) {
    const double w = 2.0 * 3.14159265358979323846 * freqHz / sampleRate;
    const double cw = std::cos(w);
    const double c2w = std::cos(2.0 * w);
    const double num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 + 2.0 * (c.b0 * c.b1 + c.b1 * c.b2) * cw +
                       2.0 * c.b0 * c.b2 * c2w;
    const double den = 1.0 + c.a1 * c.a1 + c.a2 * c.a2 + 2.0 * (c.a1 + c.a1 * c.a2) * cw + 2.0 * c.a2 * c2w;
    if (den <= 0.0 || num <= 0.0) {
        return -300.0;
    }
    return 10.0 * std::log10(num / den);
}

// A cascade of up to kMaxBands biquads, one set of filter memories per channel, in double precision.
//
// The control side (setBands / configure) runs on any one non-real-time thread at a time; process()
// runs on the audio thread and never locks, allocates or waits:
//  * band parameters travel through relaxed atomics guarded by a sequence counter — if the audio
//    thread catches an update half-written it simply keeps the old values until the next block;
//  * parameters glide to their new values (~10 ms) instead of jumping, so dragging a slider is
//    click-free; the filter coefficients are recalculated every 64 frames only while a glide runs;
//  * enabling / disabling cross-fades (wet/dry), and with nothing to do the stage is skipped, which
//    keeps an unequalised signal bit-exact;
//  * "auto headroom" turns the whole stage down by the highest boost of the combined response, so
//    boosting a band can never push the signal into clipping.
class EqChain {
public:
    static constexpr int kMaxBands = 32;
    static constexpr int kMaxChannels = 8;

    // ----- control thread ---------------------------------------------------------------

    void setEnabled(bool enabled) { mEnabled.store(enabled, std::memory_order_relaxed); }

    void setBands(const EqBandParams *bands, int count, bool autoHeadroom) {
        std::lock_guard<std::mutex> lock(mControlMutex);
        count = std::max(0, std::min(count, kMaxBands));
        for (int i = 0; i < count; i++) {
            mControlBands[i] = bands[i];
        }
        mControlCount = count;
        mControlAutoHeadroom = autoHeadroom;
        publishLocked();
    }

    // The output stream (re)opened: coefficients depend on the sample rate.
    void configure(double sampleRate) {
        std::lock_guard<std::mutex> lock(mControlMutex);
        mSampleRate.store(sampleRate > 0.0 ? sampleRate : 48000.0, std::memory_order_relaxed);
        publishLocked();
        mResetRequested.store(true, std::memory_order_release);
    }

    // How far the stage is turned down to leave room for the boosts (≤ 0 dB).
    double headroomDb() const { return mHeadroomDb.load(std::memory_order_relaxed); }

    // ----- audio thread -----------------------------------------------------------------

    // True if the last process() call changed the signal at all.
    bool wasActive() const { return mLastActive; }

    void process(double *data, size_t frames, int channels) {
        if (frames == 0 || channels <= 0) {
            return;
        }
        const double sampleRate = mSampleRate.load(std::memory_order_relaxed);
        if (mResetRequested.exchange(false, std::memory_order_acq_rel) || sampleRate != mStateRate) {
            mStateRate = sampleRate;
            mSmoothCoeff = 1.0 - std::exp(-1.0 / (0.010 * sampleRate));
            for (int i = 0; i < kMaxBands; i++) {
                mBands[i].snap = true;  // new coefficients at once, memories cleared
            }
            mAppliedSeq = ~0u;          // force a re-read
        }
        syncParameters();

        const bool wantEq = mEnabled.load(std::memory_order_relaxed) && mActiveCount > 0;
        const double target = wantEq ? 1.0 : 0.0;
        if (!wantEq && mMix == 0.0) {
            if (mStatesDirty) clearMemories();
            mLastActive = false;
            return;
        }
        mStatesDirty = true;
        mLastActive = true;

        const int chans = std::min(channels, kMaxChannels);
        const double headroom = mHeadroomLinear.load(std::memory_order_relaxed);
        if (!mGainInitialised) {
            mWetGain = headroom;
            mGainInitialised = true;
        }

        size_t done = 0;
        while (done < frames) {
            const size_t chunk = std::min<size_t>(kSubBlock, frames - done);
            stepGlide();
            for (size_t f = 0; f < chunk; f++) {
                double *frame = data + (done + f) * static_cast<size_t>(channels);
                mMix += (target - mMix) * mSmoothCoeff;
                mWetGain += (headroom - mWetGain) * mSmoothCoeff;
                for (int c = 0; c < chans; c++) {
                    const double dry = frame[c];
                    double y = dry;
                    for (int k = 0; k < mActiveCount; k++) {
                        Band &b = mBands[mActive[k]];
                        const double out = b.c.b0 * y + b.z1[c];
                        b.z1[c] = b.c.b1 * y - b.c.a1 * out + b.z2[c];
                        b.z2[c] = b.c.b2 * y - b.c.a2 * out;
                        y = out;
                    }
                    frame[c] = dry + (y * mWetGain - dry) * mMix;
                }
            }
            done += chunk;
        }
        if (!wantEq && mMix < 1e-7) {
            mMix = 0.0;  // the fade-out is over: from now on the stage is skipped
        } else if (wantEq && mMix > 1.0 - 1e-9) {
            mMix = 1.0;
        }
        // Denormal-range filter memory makes some CPUs crawl.
        for (int k = 0; k < mActiveCount; k++) {
            Band &b = mBands[mActive[k]];
            for (int c = 0; c < chans; c++) {
                if (std::fabs(b.z1[c]) < 1e-30) b.z1[c] = 0.0;
                if (std::fabs(b.z2[c]) < 1e-30) b.z2[c] = 0.0;
            }
        }
    }

private:
    static constexpr size_t kSubBlock = 64;

    struct SharedBand {
        std::atomic<int> type{kEqPeak};
        std::atomic<double> freq{1000.0};
        std::atomic<double> gain{0.0};
        std::atomic<double> q{1.0};
        std::atomic<bool> enabled{false};
    };

    struct Band {
        int type = kEqPeak;
        bool enabled = false;
        bool active = false;
        bool snap = true;
        double freqT = 1000.0, gainT = 0.0, qT = 1.0;  // where the user wants it
        double freqC = 1000.0, gainC = 0.0, qC = 1.0;  // where the filter is now
        BiquadCoeffs c;
        double z1[kMaxChannels] = {};
        double z2[kMaxChannels] = {};
    };

    static bool hasGain(int type) { return type == kEqPeak || type == kEqLowShelf || type == kEqHighShelf; }

    static bool isIdentity(int type, double gainDb) { return hasGain(type) && std::fabs(gainDb) < 0.001; }

    // --- control side ---

    // Called with mControlMutex held.
    void publishLocked() {
        const double fs = mSampleRate.load(std::memory_order_relaxed);
        mSeq.fetch_add(1, std::memory_order_acq_rel);  // odd: write in progress
        mSharedCount.store(mControlCount, std::memory_order_relaxed);
        for (int i = 0; i < mControlCount; i++) {
            mShared[i].type.store(mControlBands[i].type, std::memory_order_relaxed);
            mShared[i].freq.store(mControlBands[i].freqHz, std::memory_order_relaxed);
            mShared[i].gain.store(mControlBands[i].gainDb, std::memory_order_relaxed);
            mShared[i].q.store(mControlBands[i].q, std::memory_order_relaxed);
            mShared[i].enabled.store(mControlBands[i].enabled, std::memory_order_relaxed);
        }
        mSeq.fetch_add(1, std::memory_order_release);  // even: consistent

        double headroomDb = 0.0;
        if (mControlAutoHeadroom) {
            headroomDb = -std::max(0.0, peakGainDb(mControlBands, mControlCount, fs));
        }
        mHeadroomDb.store(headroomDb, std::memory_order_relaxed);
        mHeadroomLinear.store(std::pow(10.0, headroomDb / 20.0), std::memory_order_relaxed);
    }

public:
    // Highest point of the combined response of [bands] (dB), looked for on a fine log grid plus at
    // every band's own centre frequency. Public so tests and tools can use the same number.
    static double peakGainDb(const EqBandParams *bands, int count, double fs) {
        BiquadCoeffs coeffs[kMaxBands];
        int n = 0;
        for (int i = 0; i < count && n < kMaxBands; i++) {
            if (!bands[i].enabled || isIdentity(bands[i].type, bands[i].gainDb)) continue;
            coeffs[n++] = designBiquad(bands[i].type, bands[i].freqHz, bands[i].gainDb, bands[i].q, fs);
        }
        if (n == 0) return 0.0;
        const double top = 0.49 * fs;
        double peak = -300.0;
        const int kGrid = 600;
        for (int g = 0; g <= kGrid + count; g++) {
            double f;
            if (g <= kGrid) {
                f = 10.0 * std::pow(top / 10.0, static_cast<double>(g) / kGrid);
            } else {
                f = std::min(std::max(bands[g - kGrid - 1].freqHz, 10.0), top);  // exact band centres
            }
            double sum = 0.0;
            for (int k = 0; k < n; k++) sum += biquadMagnitudeDb(coeffs[k], f, fs);
            peak = std::max(peak, sum);
        }
        return peak;
    }

private:
    // --- audio side ---

    // Picks up a new parameter set if one is complete; otherwise keeps what it has.
    void syncParameters() {
        const uint32_t seq = mSeq.load(std::memory_order_acquire);
        if (seq == mAppliedSeq || (seq & 1u)) {
            return;
        }
        const int count = mSharedCount.load(std::memory_order_relaxed);
        EqBandParams local[kMaxBands];
        for (int i = 0; i < count && i < kMaxBands; i++) {
            local[i].type = mShared[i].type.load(std::memory_order_relaxed);
            local[i].freqHz = mShared[i].freq.load(std::memory_order_relaxed);
            local[i].gainDb = mShared[i].gain.load(std::memory_order_relaxed);
            local[i].q = mShared[i].q.load(std::memory_order_relaxed);
            local[i].enabled = mShared[i].enabled.load(std::memory_order_relaxed);
        }
        std::atomic_thread_fence(std::memory_order_acquire);
        if (mSeq.load(std::memory_order_relaxed) != seq) {
            return;  // the writer got in the middle of this; try again next block
        }
        mAppliedSeq = seq;

        for (int i = 0; i < kMaxBands; i++) {
            Band &b = mBands[i];
            if (i >= count) {
                b.enabled = false;
                b.gainT = 0.0;
                if (!hasGain(b.type)) b.active = false;
                continue;
            }
            if (b.type != local[i].type) {
                b.snap = true;  // a different kind of filter: no sensible glide between the two
                b.active = false;
            }
            b.type = local[i].type;
            b.enabled = local[i].enabled;
            b.freqT = local[i].freqHz;
            b.gainT = local[i].gainDb;
            b.qT = local[i].q;
        }
        rebuildActiveList();
    }

    // What a band is heading for: a disabled boost / cut glides back to flat instead of vanishing.
    static double effectiveGain(const Band &b) { return (hasGain(b.type) && !b.enabled) ? 0.0 : b.gainT; }

    void rebuildActiveList() {
        mActiveCount = 0;
        for (int i = 0; i < kMaxBands; i++) {
            Band &b = mBands[i];
            const double gT = effectiveGain(b);
            const bool shouldRun = hasGain(b.type)
                    ? !(isIdentity(b.type, gT) && isIdentity(b.type, b.gainC))
                    : b.enabled;
            if (shouldRun && !b.active) {
                // A band joining in. Boosts / cuts start from flat and glide to their value, so there
                // is nothing to click; other kinds start at once.
                std::fill(std::begin(b.z1), std::end(b.z1), 0.0);
                std::fill(std::begin(b.z2), std::end(b.z2), 0.0);
                b.freqC = b.freqT;
                b.qC = b.qT;
                b.gainC = hasGain(b.type) ? 0.0 : b.gainT;
                b.snap = false;
                b.c = designBiquad(b.type, b.freqC, b.gainC, b.qC, mStateRate);
            } else if (!shouldRun) {
                // Parked: keep position / shape current so the next start is from the right place.
                b.freqC = b.freqT;
                b.qC = b.qT;
                if (hasGain(b.type)) b.gainC = 0.0;
            }
            b.active = shouldRun;
            if (shouldRun) mActive[mActiveCount++] = i;
        }
    }

    // One step of every band's glide towards its target; recalculates coefficients where needed.
    void stepGlide() {
        bool rebuild = false;
        for (int k = 0; k < mActiveCount; k++) {
            Band &b = mBands[mActive[k]];
            const double gT = effectiveGain(b);
            if (b.snap) {
                b.freqC = b.freqT;
                b.gainC = gT;
                b.qC = b.qT;
                b.snap = false;
                std::fill(std::begin(b.z1), std::end(b.z1), 0.0);
                std::fill(std::begin(b.z2), std::end(b.z2), 0.0);
                b.c = designBiquad(b.type, b.freqC, b.gainC, b.qC, mStateRate);
                continue;
            }
            const double dg = gT - b.gainC;
            const double dq = b.qT - b.qC;
            const double lf = std::log(std::max(b.freqT, 1.0) / std::max(b.freqC, 1.0));
            if (std::fabs(dg) < 1e-4 && std::fabs(dq) < 1e-5 && std::fabs(lf) < 1e-5) {
                if (b.gainC != gT || b.qC != b.qT || b.freqC != b.freqT) {
                    b.gainC = gT;
                    b.qC = b.qT;
                    b.freqC = b.freqT;
                    b.c = designBiquad(b.type, b.freqC, b.gainC, b.qC, mStateRate);
                }
                if (isIdentity(b.type, gT) && isIdentity(b.type, b.gainC)) rebuild = true;  // flat again: stop running it
                continue;
            }
            const double k2 = 0.35;
            b.gainC += dg * k2;
            b.qC += dq * k2;
            b.freqC *= std::exp(lf * k2);
            b.c = designBiquad(b.type, b.freqC, b.gainC, b.qC, mStateRate);
        }
        if (rebuild) rebuildActiveList();
    }

    void clearMemories() {
        for (int i = 0; i < kMaxBands; i++) {
            std::fill(std::begin(mBands[i].z1), std::end(mBands[i].z1), 0.0);
            std::fill(std::begin(mBands[i].z2), std::end(mBands[i].z2), 0.0);
        }
        mStatesDirty = false;
        mGainInitialised = false;
    }

    // shared with the control thread
    std::mutex mControlMutex;
    EqBandParams mControlBands[kMaxBands];
    int mControlCount = 0;
    bool mControlAutoHeadroom = true;

    SharedBand mShared[kMaxBands];
    std::atomic<int> mSharedCount{0};
    std::atomic<uint32_t> mSeq{0};
    std::atomic<bool> mEnabled{false};
    std::atomic<double> mSampleRate{48000.0};
    std::atomic<bool> mResetRequested{true};
    std::atomic<double> mHeadroomDb{0.0};
    std::atomic<double> mHeadroomLinear{1.0};

    // audio thread only
    Band mBands[kMaxBands];
    int mActive[kMaxBands] = {};
    int mActiveCount = 0;
    uint32_t mAppliedSeq = ~0u;
    double mStateRate = 0.0;
    double mSmoothCoeff = 0.002;
    double mMix = 0.0;
    double mWetGain = 1.0;
    bool mGainInitialised = false;
    bool mStatesDirty = false;
    bool mLastActive = false;
};

}  // namespace resonix

#endif  // RESONIX_EQ_H
