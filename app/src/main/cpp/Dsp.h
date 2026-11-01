#ifndef RESONIX_DSP_H
#define RESONIX_DSP_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "Limiter.h"

namespace resonix {

// The double-precision processing that sits between the ring buffer and the
// float32 cast Oboe sees:
//
//   bass shelf -> stereo width -> balance -> (master * ReplayGain * preamp) -> peak limiter
//
// Every parameter is an atomic, so the UI thread can change it at any time while
// the audio thread is inside process(). process() itself never allocates or locks.
//
// With every parameter at its neutral value the chain is an exact pass-through
// (x * 1.0 == x in IEEE arithmetic, and the optional stages are skipped), so the
// default configuration stays bit-perfect. The limiter only engages when the chain
// is able to raise the level (total gain above unity, bass boost, or a wider image),
// otherwise it would alter loud masters that peak between 0.98 and 1.0.
class DspChain {
public:
    static constexpr int kMaxChannels = 8;

    // Called whenever the output stream (re)opens. Never while process() is running.
    void configure(double sampleRateHz, int channelCount) {
        mSampleRate.store(sampleRateHz > 0.0 ? sampleRateHz : 48000.0, std::memory_order_relaxed);
        mChannels.store(channelCount, std::memory_order_relaxed);
        mResetRequested.store(true, std::memory_order_release);
    }

    // --- parameters (any thread) ------------------------------------------------

    // Direct volume control × audio-focus ducking. Linear, 1.0 = unity.
    void setMasterGain(double linear) { mMaster.store(clampd(linear, 0.0, 4.0), std::memory_order_relaxed); }

    // REPLAYGAIN_* adjustment for the loaded track. Linear, 1.0 = none.
    void setReplayGain(double linear) { mReplay.store(clampd(linear, 0.0, 16.0), std::memory_order_relaxed); }

    void setPreampDb(double db) {
        mPreamp.store(std::pow(10.0, clampd(db, -12.0, 12.0) / 20.0), std::memory_order_relaxed);
    }

    // 0 … 1: how much low-shelf boost (0 = off, 1 = +10 dB below ~120 Hz).
    void setBassBoost(double amount) { mBass.store(clampd(amount, 0.0, 1.0), std::memory_order_relaxed); }

    // 0 … 2: 0 = mono, 1 = original stereo image, 2 = twice as wide.
    void setStereoWidth(double width) { mWidth.store(clampd(width, 0.0, 2.0), std::memory_order_relaxed); }

    // -1 (left) … +1 (right).
    void setBalance(double balance) { mBalance.store(clampd(balance, -1.0, 1.0), std::memory_order_relaxed); }

    void setLimiterEnabled(bool enabled) { mLimiterEnabled.store(enabled, std::memory_order_relaxed); }

    // --- audio thread -----------------------------------------------------------

    // True if the last process() call left every sample exactly as it came in: unity gain, no bass
    // boost, untouched stereo image, centred balance. Only meaningful on the audio thread. The
    // output stage uses it to know when dither must NOT be added (a bit-perfect signal stays so).
    bool lastBlockWasNeutral() const { return mLastNeutral; }

    // Processes [frames] interleaved frames in place.
    void process(double *data, size_t frames, int channels) {
        if (frames == 0 || channels <= 0) {
            return;
        }
        const double sampleRate = mSampleRate.load(std::memory_order_relaxed);
        if (mResetRequested.exchange(false, std::memory_order_acq_rel) || sampleRate != mStateRate) {
            resetState(sampleRate);
        }

        const double targetGain = mMaster.load(std::memory_order_relaxed) *
                                  mReplay.load(std::memory_order_relaxed) *
                                  mPreamp.load(std::memory_order_relaxed);
        const double bass = mBass.load(std::memory_order_relaxed);
        const double width = mWidth.load(std::memory_order_relaxed);
        const double balance = mBalance.load(std::memory_order_relaxed);
        const bool limiterOn = mLimiterEnabled.load(std::memory_order_relaxed);

        const bool stereo = (channels == 2);
        const bool doBass = bass > 0.001;
        const bool doWidth = stereo && std::fabs(width - 1.0) > 0.001;
        const double targetBalL = balance > 0.0 ? 1.0 - balance : 1.0;
        const double targetBalR = balance < 0.0 ? 1.0 + balance : 1.0;

        if (!mInitialised) {  // first buffer after a (re)configure: no fade-in from a stale value
            mGain = targetGain;
            mBalL = targetBalL;
            mBalR = targetBalR;
            mInitialised = true;
        }

        if (doBass) {
            if (std::fabs(bass - mBassDesigned) > 1e-6) {
                designBassShelf(bass, sampleRate);
            }
        } else if (mBassDesigned != 0.0) {
            // Switched off: forget the filter memory so re-enabling doesn't click.
            for (int c = 0; c < kMaxChannels; c++) {
                mBassFilter[c].reset();
            }
            mBassDesigned = 0.0;
        }

        // Anything that can push samples above their original level is a reason to limit.
        const bool canBoost = targetGain > 1.0001 || mGain > 1.0001 || doBass || (doWidth && width > 1.0);
        const bool limit = limiterOn && canBoost;
        if (!limit) {
            mLimiter.reset();
        }

        const int biquadChannels = std::min(channels, kMaxChannels);
        const double smooth = mSmoothCoeff;

        for (size_t f = 0; f < frames; f++) {
            double *frame = data + f * static_cast<size_t>(channels);

            if (doBass) {
                for (int c = 0; c < biquadChannels; c++) {
                    frame[c] = mBassFilter[c].process(frame[c]);
                }
            }

            if (doWidth) {
                const double mid = (frame[0] + frame[1]) * 0.5;
                const double side = (frame[0] - frame[1]) * 0.5 * width;
                frame[0] = mid + side;
                frame[1] = mid - side;
            }

            mGain += (targetGain - mGain) * smooth;
            if (stereo) {
                mBalL += (targetBalL - mBalL) * smooth;
                mBalR += (targetBalR - mBalR) * smooth;
                frame[0] *= mGain * mBalL;
                frame[1] *= mGain * mBalR;
            } else {
                for (int c = 0; c < channels; c++) {
                    frame[c] *= mGain;
                }
            }

            if (limit) {
                const double reduction = mLimiter.process(frame, std::min(channels, kMaxChannels));
                if (reduction < 1.0) {
                    for (int c = 0; c < channels; c++) {
                        frame[c] *= reduction;
                    }
                }
            }

            // Last line of defence: never hand the hardware a sample beyond full scale.
            for (int c = 0; c < channels; c++) {
                frame[c] = clampd(frame[c], -1.0, 1.0);
            }
        }

        mLastNeutral = !doBass && !doWidth && std::fabs(targetGain - 1.0) < 1e-9 &&
                       std::fabs(mGain - 1.0) < 1e-9 && std::fabs(mBalL - 1.0) < 1e-9 &&
                       std::fabs(mBalR - 1.0) < 1e-9;

        // Filter memory that has decayed to the denormal range makes some CPUs crawl; flush it.
        if (doBass) {
            for (int c = 0; c < kMaxChannels; c++) {
                if (std::fabs(mBassFilter[c].z1) < 1e-30) mBassFilter[c].z1 = 0.0;
                if (std::fabs(mBassFilter[c].z2) < 1e-30) mBassFilter[c].z2 = 0.0;
            }
        }
    }

private:
    static double clampd(double v, double lo, double hi) {
        if (!(v == v)) {  // NaN
            return lo > 0.0 ? lo : (hi < 0.0 ? hi : 0.0);
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // Direct form II transposed biquad.
    struct Biquad {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        double z1 = 0.0, z2 = 0.0;

        double process(double x) {
            const double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = 0.0;
            z2 = 0.0;
        }
    };

    void resetState(double sampleRate) {
        mStateRate = sampleRate;
        mSmoothCoeff = 1.0 - std::exp(-1.0 / (0.010 * sampleRate));  // ~10 ms glide for gain changes
        mInitialised = false;
        mBassDesigned = 0.0;
        for (int c = 0; c < kMaxChannels; c++) {
            mBassFilter[c] = Biquad();
        }
        mLimiter.configure(sampleRate);
    }

    // RBJ cookbook low shelf, shelf slope 1, corner 120 Hz, up to +10 dB.
    void designBassShelf(double amount, double sampleRate) {
        const double gainDb = 10.0 * amount;
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * 3.14159265358979323846 * 120.0 / sampleRate;
        const double cosw = std::cos(w0);
        const double alpha = std::sin(w0) / std::sqrt(2.0);
        const double sqrtA2alpha = 2.0 * std::sqrt(A) * alpha;

        const double b0 = A * ((A + 1.0) - (A - 1.0) * cosw + sqrtA2alpha);
        const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw);
        const double b2 = A * ((A + 1.0) - (A - 1.0) * cosw - sqrtA2alpha);
        const double a0 = (A + 1.0) + (A - 1.0) * cosw + sqrtA2alpha;
        const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw);
        const double a2 = (A + 1.0) + (A - 1.0) * cosw - sqrtA2alpha;

        for (int c = 0; c < kMaxChannels; c++) {
            // Keep each channel's memory: coefficients change smoothly while the knob turns.
            mBassFilter[c].b0 = b0 / a0;
            mBassFilter[c].b1 = b1 / a0;
            mBassFilter[c].b2 = b2 / a0;
            mBassFilter[c].a1 = a1 / a0;
            mBassFilter[c].a2 = a2 / a0;
        }
        mBassDesigned = amount;
    }

    std::atomic<double> mMaster{1.0};
    std::atomic<double> mReplay{1.0};
    std::atomic<double> mPreamp{1.0};
    std::atomic<double> mBass{0.0};
    std::atomic<double> mWidth{1.0};
    std::atomic<double> mBalance{0.0};
    std::atomic<bool> mLimiterEnabled{true};

    std::atomic<double> mSampleRate{48000.0};
    std::atomic<int> mChannels{2};
    std::atomic<bool> mResetRequested{true};

    // Audio-thread state.
    double mStateRate = 0.0;
    double mSmoothCoeff = 0.002;
    bool mInitialised = false;
    bool mLastNeutral = true;
    double mGain = 1.0;
    double mBalL = 1.0;
    double mBalR = 1.0;
    double mBassDesigned = 0.0;
    Biquad mBassFilter[kMaxChannels];
    PeakLimiter mLimiter;
};

}  // namespace resonix

#endif  // RESONIX_DSP_H
