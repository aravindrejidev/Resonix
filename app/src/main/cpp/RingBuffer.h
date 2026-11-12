#ifndef RESONIX_RING_BUFFER_H
#define RESONIX_RING_BUFFER_H

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
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
// logically unbounded. They are explicitly 64-bit: with a 32-bit size_t
// (armeabi-v7a) the index would wrap after ~3 hours of 192 kHz stereo,
// and because the capacity is not a power of two the wrap would put the
// read position in the wrong place. A 64-bit index takes millions of years.
//
// The absolute indices are also public ([writeIndex] / [readIndex]): the
// engine marks the exact sample where one track ends and the next begins
// (gapless playback) as a write index and notices when the reader passes it.
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
        const uint64_t writeIdx = mWriteIndex.load(std::memory_order_relaxed);
        const uint64_t readIdx = mReadIndex.load(std::memory_order_acquire);
        const uint64_t used = writeIdx >= readIdx ? writeIdx - readIdx : 0;
        const size_t freeSpace = used < mCapacity ? mCapacity - static_cast<size_t>(used) : 0;
        const size_t toWrite = std::min(count, freeSpace);

        if (toWrite > 0) {
            // At most two straight copies (up to the end of the storage, then from its start).
            const size_t start = static_cast<size_t>(writeIdx % mCapacity);
            const size_t first = std::min(toWrite, mCapacity - start);
            std::memcpy(mBuffer.get() + start, data, first * sizeof(double));
            if (toWrite > first) {
                std::memcpy(mBuffer.get(), data + first, (toWrite - first) * sizeof(double));
            }
        }

        mWriteIndex.store(writeIdx + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer side (Oboe audio callback only). Returns how many samples
    // were actually read; less than count means underrun — the caller
    // must fill the remainder with silence, never block waiting.
    //
    // The read index is committed with a compare-exchange: if the producer
    // flushed the buffer (flushFromProducer) while this call was copying
    // out, the exchange fails and the chunk is dropped (reported as an
    // underrun) instead of replaying stale audio or corrupting the indices.
    size_t read(double *data, size_t count) {
        uint64_t readIdx = mReadIndex.load(std::memory_order_acquire);
        const uint64_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        const uint64_t available = writeIdx >= readIdx ? writeIdx - readIdx : 0;
        const size_t toRead = static_cast<size_t>(std::min<uint64_t>(std::min<uint64_t>(count, available), mCapacity));
        if (toRead == 0) {
            return 0;
        }

        const size_t start = static_cast<size_t>(readIdx % mCapacity);
        const size_t first = std::min(toRead, mCapacity - start);
        std::memcpy(data, mBuffer.get() + start, first * sizeof(double));
        if (toRead > first) {
            std::memcpy(data + first, mBuffer.get(), (toRead - first) * sizeof(double));
        }

        if (!mReadIndex.compare_exchange_strong(readIdx, readIdx + toRead,
                                                std::memory_order_acq_rel,
                                                std::memory_order_acquire)) {
            return 0;  // flushed underneath us — treat as an underrun
        }
        return toRead;
    }

    size_t availableToRead() const {
        // Read index first: it can never pass the write index, so loading
        // the write index second guarantees write >= read here.
        const uint64_t readIdx = mReadIndex.load(std::memory_order_acquire);
        const uint64_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        return writeIdx >= readIdx ? static_cast<size_t>(writeIdx - readIdx) : 0;
    }

    // Absolute positions (samples written / read since construction; never reset by a flush).
    uint64_t writeIndex() const { return mWriteIndex.load(std::memory_order_acquire); }
    uint64_t readIndex() const { return mReadIndex.load(std::memory_order_acquire); }

    // Discards everything currently buffered. Call it from the producer
    // side only (the decode thread, or whoever is acting as the producer
    // while the decode thread is stopped). It never touches mWriteIndex,
    // and moving the read index up to it is safe even while the audio
    // callback is running, because read() commits with a compare-exchange.
    void flushFromProducer() {
        mReadIndex.store(mWriteIndex.load(std::memory_order_acquire),
                         std::memory_order_release);
    }

    // Hard reset of both indices. Only safe when BOTH producer and
    // consumer are stopped — prefer flushFromProducer() anywhere the
    // audio callback could still be running.
    void reset() {
        mReadIndex.store(0, std::memory_order_relaxed);
        mWriteIndex.store(0, std::memory_order_relaxed);
    }

private:
    const size_t mCapacity;
    std::unique_ptr<double[]> mBuffer;
    std::atomic<uint64_t> mWriteIndex{0};
    std::atomic<uint64_t> mReadIndex{0};
};

}  // namespace resonix

#endif  // RESONIX_RING_BUFFER_H
