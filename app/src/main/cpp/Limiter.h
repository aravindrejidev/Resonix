#ifndef RESONIX_LIMITER_H
#define RESONIX_LIMITER_H

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace resonix {

// Brickwall-ish peak limiter, applied last in the DSP chain (after DVC
// and ReplayGain, right before the float32 cast). Deliberately has no
// lookahead: a lookahead limiter needs a delay line to scan upcoming
// samples before committing to an output, which adds latency that
// conflicts with this app's low-latency AAudio Exclusive-mode design.
// The tradeoff is that an extremely sharp, isolated transient can very
// briefly graze past the ceiling for a sample or two before the fast
// attack catches it — inaudible in practice, and the standard tradeoff
// every non-lookahead limiter makes.
//
// Not thread-safe; owned and called only from the Oboe audio callback
// (see AudioEngine::onAudioReady), so this is fine.
class PeakLimiter {
public:
    void configure(double sampleRateHz) {
        constexpr double kAttackMs = 5.0;
        constexpr double kReleaseMs = 100.0;
        mAttackCoeff = std::exp(-1.0 / (0.001 * kAttackMs * sampleRateHz));
        mReleaseCoeff = std::exp(-1.0 / (0.001 * kReleaseMs * sampleRateHz));
        reset();
    }

    void reset() {
        mCurrentGain = 1.0;
    }

    // Call once per output frame with that frame's (post DVC/ReplayGain)
    // samples across all channels. Returns a single gain-reduction
    // factor (<= 1.0) to multiply into every channel of this frame —
    // shared across channels so limiting never shifts the stereo image.
    double process(const double *frameSamples, int32_t channelCount) {
        double peakInFrame = 0.0;
        for (int32_t ch = 0; ch < channelCount; ch++) {
            peakInFrame = std::max(peakInFrame, std::abs(frameSamples[ch]));
        }

        double desiredGain = (peakInFrame > kCeiling) ? (kCeiling / peakInFrame) : 1.0;

        // Fast attack when reducing gain further, slower release when
        // recovering back toward unity — standard limiter behavior.
        double coeff = (desiredGain < mCurrentGain) ? mAttackCoeff : mReleaseCoeff;
        mCurrentGain = desiredGain + coeff * (mCurrentGain - desiredGain);

        return mCurrentGain;
    }

private:
    // Slightly below true 0dBFS (~-0.18dB) — standard headroom margin
    // below full scale, not just up to it.
    static constexpr double kCeiling = 0.98;

    double mCurrentGain = 1.0;
    double mAttackCoeff = 0.0;
    double mReleaseCoeff = 0.0;
};

}  // namespace resonix

#endif  // RESONIX_LIMITER_H
