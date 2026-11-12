#include "RingBuffer.h"
#include <cstdio>
#include <vector>
using namespace resonix;
int main() {
    // capacity is deliberately not a power of two, like the real one is not required to be
    RingBuffer rb(1000003);
    std::vector<double> in(65536 + 7), out(65536 + 7);
    uint64_t wrote = 0, read = 0;
    const uint64_t target = (1ull << 32) + 5000000ull;   // past the point where a 32-bit index would wrap
    uint64_t bad = 0;
    while (read < target) {
        size_t chunk = 1 + (wrote % 65536);
        for (size_t i = 0; i < chunk; i++) in[i] = (double)(wrote + i);
        size_t w = rb.write(in.data(), chunk);
        wrote += w;
        size_t want = 1 + (read * 7 % 65536);
        size_t r = rb.read(out.data(), want);
        for (size_t i = 0; i < r; i++) if (out[i] != (double)(read + i)) { if (!bad) std::printf("first mismatch at %llu\n", (unsigned long long)(read + i)); bad++; break; }
        read += r;
    }
    std::printf("read %llu samples (2^32 = %llu), index=%llu, mismatches=%llu\n", (unsigned long long)read, 1ull << 32, (unsigned long long)rb.readIndex(), (unsigned long long)bad);
    // flush keeps absolute indices
    rb.flushFromProducer();
    std::printf("after flush: available=%zu read==write: %d\n", rb.availableToRead(), rb.readIndex() == rb.writeIndex());
    return bad ? 1 : 0;
}
