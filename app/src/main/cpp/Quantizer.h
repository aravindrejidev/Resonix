#ifndef RESONIX_QUANTIZER_H
#define RESONIX_QUANTIZER_H

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace resonix {

// What the output stream is fed. The values are shared with the Kotlin side (OutputFormat).
enum SampleFormat : int {
    kFormatFloat = 0,  // 32-bit float
    kFormatI16 = 1,
    kFormatI24 = 2,  // packed, 3 bytes per sample, little endian
    kFormatI32 = 3,
};

inline int bitsOfFormat(int format) {
    switch (format) {
        case kFormatI16: return 16;
        case kFormatI24: return 24;
        default: return 32;
    }
}

inline size_t bytesPerSample(int format) {
    switch (format) {
        case kFormatI16: return 2;
        case kFormatI24: return 3;
        default: return 4;
    }
}

// The very last step of the signal path: the double-precision samples (nominally ±1.0) become
// whatever the output stream wants.
//
//  * Float and 32-bit are plain conversions (a 32-bit integer already has more resolution than any
//    DAC; float32 has 24 bits of mantissa).
//  * 16- and 24-bit are rounded to the nearest step. With [dither] the rounding error is first
//    decorrelated from the signal by adding triangular (TPDF) noise of ±1 LSB, which turns the
//    distortion that plain rounding adds to quiet passages into a constant, much less audible hiss.
//
// Callers must skip the dither when the samples are already exactly on the output's grid (an
// unprocessed 16-bit file played to a 16-bit stream), otherwise it would only add noise.
class Quantizer {
public:
    void convert(const double *in, void *out, size_t sampleCount, int format, bool dither) {
        switch (format) {
            case kFormatI16: {
                auto *o = static_cast<int16_t *>(out);
                for (size_t i = 0; i < sampleCount; i++) {
                    double v = scaled(in[i], 32768.0, dither);
                    long q = std::lrint(v);
                    if (q > 32767) q = 32767;
                    if (q < -32768) q = -32768;
                    o[i] = static_cast<int16_t>(q);
                }
                break;
            }
            case kFormatI24: {
                auto *o = static_cast<uint8_t *>(out);
                for (size_t i = 0; i < sampleCount; i++) {
                    double v = scaled(in[i], 8388608.0, dither);
                    long q = std::lrint(v);
                    if (q > 8388607) q = 8388607;
                    if (q < -8388608) q = -8388608;
                    const auto u = static_cast<uint32_t>(static_cast<int32_t>(q));
                    o[3 * i] = static_cast<uint8_t>(u & 0xFF);
                    o[3 * i + 1] = static_cast<uint8_t>((u >> 8) & 0xFF);
                    o[3 * i + 2] = static_cast<uint8_t>((u >> 16) & 0xFF);
                }
                break;
            }
            case kFormatI32: {
                auto *o = static_cast<int32_t *>(out);
                for (size_t i = 0; i < sampleCount; i++) {
                    double v = in[i];
                    if (!(v == v)) v = 0.0;
                    v *= 2147483648.0;
                    if (v >= 2147483647.0) {
                        o[i] = INT32_MAX;
                    } else if (v <= -2147483648.0) {
                        o[i] = INT32_MIN;
                    } else {
                        o[i] = static_cast<int32_t>(std::lrint(v));
                    }
                }
                break;
            }
            default: {
                auto *o = static_cast<float *>(out);
                for (size_t i = 0; i < sampleCount; i++) {
                    o[i] = static_cast<float>(in[i]);
                }
                break;
            }
        }
    }

private:
    // [x] in LSB units (full scale = [scale] LSB), with TPDF dither added when asked.
    double scaled(double x, double scale, bool dither) {
        if (!(x == x)) x = 0.0;
        double v = x * scale;
        if (dither) v += uniform() + uniform();
        return v;
    }

    // xorshift64*: uniform in [-0.5, 0.5). Not cryptographic — it only has to be white and cheap.
    double uniform() {
        mState ^= mState >> 12;
        mState ^= mState << 25;
        mState ^= mState >> 27;
        const uint64_t r = mState * 0x2545F4914F6CDD1DULL;
        return static_cast<double>(r >> 11) * (1.0 / 9007199254740992.0) - 0.5;
    }

    uint64_t mState = 0x9E3779B97F4A7C15ULL;
};

}  // namespace resonix

#endif  // RESONIX_QUANTIZER_H
