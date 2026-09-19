#ifndef RESONIX_RING_BUFFER_H
#define RESONIX_RING_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <memory>

namespace resonix {

// Single-producer/single-consumer lock-free ring buffer of interleaved
// double-precision samples.
//
// Safety depends on exactly one thread ever calling write() (the decode
// thread) and exactly one thread ever calling read() (the Oboe audio
// callback thread). Neither side allocates, locks, or blocks — read()
// and write() both return the number of samples actually moved, and
// the caller handles a partial result (silence-fill on underrun for
// the reader; retry/backoff on a full buffer for the writer).
//
// Indices only ever increase (never wrap directly); the wrap happens
// at the point of indexing into mBuffer via modulo. This sidesteps the
// classic "how do you tell full from empty" ambiguity of a circular
// buffer that wraps its indices, at the cost of indices that are
// logically unbounded (safe in practice: at 48kHz stereo, a 64-bit
// index would take tens of millions of years to wrap).
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityInSamples)
            : mCapacity(capacityInSamples),
              mBuffer(new double[capacityInSamples]) {}

    RingBuffer(const RingBuffer &) = delete;
    RingBuffer &operator=(const RingBuffer &) = delete;

    // Producer side (decode thread only). Returns how many samples were
    // actually written; less than count means the buffer is full and
    // the caller should back off briefly and retry.
    size_t write(const double *data, size_t count) {
        size_t writeIdx = mWriteIndex.load(std::memory_order_relaxed);
        size_t readIdx = mReadIndex.load(std::memory_order_acquire);
        size_t freeSpace = mCapacity - (writeIdx - readIdx);
        size_t toWrite = std::min(count, freeSpace);

        for (size_t i = 0; i < toWrite; i++) {
            mBuffer[(writeIdx + i) % mCapacity] = data[i];
        }

        mWriteIndex.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer side (Oboe audio callback only). Returns how many samples
    // were actually read; less than count means underrun — the caller
    // must fill the remainder with silence, never block waiting.
    size_t read(double *data, size_t count) {
        size_t readIdx = mReadIndex.load(std::memory_order_relaxed);
        size_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        size_t available = writeIdx - readIdx;
        size_t toRead = std::min(count, available);

        for (size_t i = 0; i < toRead; i++) {
            data[i] = mBuffer[(readIdx + i) % mCapacity];
        }

        mReadIndex.store(readIdx + toRead, std::memory_order_release);
        return toRead;
    }

    size_t availableToRead() const {
        return mWriteIndex.load(std::memory_order_acquire) -
               mReadIndex.load(std::memory_order_relaxed);
    }

    // Only safe to call when both producer and consumer are stopped
    // (e.g. before starting a new track).
    void reset() {
        mReadIndex.store(0, std::memory_order_relaxed);
        mWriteIndex.store(0, std::memory_order_relaxed);
    }

private:
    const size_t mCapacity;
    std::unique_ptr<double[]> mBuffer;
    std::atomic<size_t> mWriteIndex{0};
    std::atomic<size_t> mReadIndex{0};
};

}  // namespace resonix

#endif  // RESONIX_RING_BUFFER_H
