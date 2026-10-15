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
        size_t used = writeIdx >= readIdx ? writeIdx - readIdx : 0;
        size_t freeSpace = used < mCapacity ? mCapacity - used : 0;
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
    //
    // The read index is committed with a compare-exchange: if the producer
    // flushed the buffer (flushFromProducer) while this call was copying
    // out, the exchange fails and the chunk is dropped (reported as an
    // underrun) instead of replaying stale audio or corrupting the indices.
    size_t read(double *data, size_t count) {
        size_t readIdx = mReadIndex.load(std::memory_order_acquire);
        size_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        size_t available = writeIdx >= readIdx ? writeIdx - readIdx : 0;
        size_t toRead = std::min(std::min(count, available), mCapacity);
        if (toRead == 0) {
            return 0;
        }

        for (size_t i = 0; i < toRead; i++) {
            data[i] = mBuffer[(readIdx + i) % mCapacity];
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
        size_t readIdx = mReadIndex.load(std::memory_order_acquire);
        size_t writeIdx = mWriteIndex.load(std::memory_order_acquire);
        return writeIdx >= readIdx ? writeIdx - readIdx : 0;
    }

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
    std::atomic<size_t> mWriteIndex{0};
    std::atomic<size_t> mReadIndex{0};
};

}  // namespace resonix

#endif  // RESONIX_RING_BUFFER_H
