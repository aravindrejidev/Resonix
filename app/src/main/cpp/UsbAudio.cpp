#include "UsbAudio.h"

#include <algorithm>
#include <cstdio>
#include <map>
#include <set>

#include "UsbTransport.h"

namespace resonix::usb {
namespace {

constexpr uint8_t kDescConfiguration = 0x02;
constexpr uint8_t kDescInterface = 0x04;
constexpr uint8_t kDescEndpoint = 0x05;
constexpr uint8_t kDescCsInterface = 0x24;

constexpr int kClassAudio = 1;
constexpr int kSubControl = 1;
constexpr int kSubStreaming = 2;

// Class-specific audio-control descriptor subtypes (UAC1 and UAC2 share the first few numbers).
constexpr int kAcHeader = 0x01;
constexpr int kAcInputTerminal = 0x02;
constexpr int kAcClockSource = 0x0A;
constexpr int kAcClockSelector = 0x0B;
constexpr int kAcClockMultiplier = 0x0C;

constexpr int kStandardRates[] = {8000,  11025, 16000, 22050,  32000,  44100,  48000, 88200,
                                  96000, 176400, 192000, 352800, 384000, 705600, 768000};

inline int u16(const uint8_t *p) { return p[0] | (p[1] << 8); }
inline int u24(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16); }
inline uint32_t u32(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

struct ClockEntity {
    int kind = 0;               // kAcClockSource / Selector / Multiplier
    int source = 0;             // multiplier: its input
    std::vector<int> pins;      // selector: its inputs
};

void addStandardRatesIn(int low, int high, int step, std::vector<int> *out) {
    for (int r : kStandardRates) {
        if (r < low || r > high) continue;
        if (step > 0 && (r - low) % step != 0) continue;
        out->push_back(r);
    }
}

void sortUnique(std::vector<int> *v) {
    std::sort(v->begin(), v->end());
    v->erase(std::unique(v->begin(), v->end()), v->end());
}

std::string rateText(int hz) {
    char buf[32];
    if (hz % 1000 == 0) {
        std::snprintf(buf, sizeof(buf), "%d kHz", hz / 1000);
    } else {
        std::snprintf(buf, sizeof(buf), "%.1f kHz", hz / 1000.0);
    }
    return buf;
}

}  // namespace

std::vector<int> DeviceCaps::rates() const {
    std::vector<int> all;
    for (const StreamFormat &f : formats) all.insert(all.end(), f.rates.begin(), f.rates.end());
    sortUnique(&all);
    return all;
}

int DeviceCaps::maxBitResolution() const {
    int best = 0;
    for (const StreamFormat &f : formats) best = std::max(best, f.bitResolution);
    return best;
}

int DeviceCaps::maxChannels() const {
    int best = 0;
    for (const StreamFormat &f : formats) best = std::max(best, f.channels);
    return best;
}

bool parseDescriptors(const uint8_t *data, size_t length, DeviceCaps *caps) {
    *caps = DeviceCaps{};
    if (data == nullptr || length < 2) return false;

    std::map<int, ClockEntity> clocks;
    std::map<int, int> terminalClock;  // UAC2 input terminal id -> the clock entity it runs on
    int firstClockSource = 0;
    int acVersion = 0;
    bool implicitFeedbackSeen = false;

    int ifaceClass = 0, ifaceSub = 0, ifaceProto = 0;
    bool inStreaming = false, haveFormat = false, capture = false, sawEndpoint = false, formatPcm = true;
    StreamFormat cur;
    std::vector<StreamFormat> found;

    auto finish = [&]() {
        if (haveFormat) {
            if (capture) {
                caps->hasCapture = true;
            } else if (formatPcm && cur.channels > 0 && cur.subslotBytes >= 2 && cur.subslotBytes <= 4 &&
                       cur.bitResolution > 0 && cur.bitResolution <= cur.subslotBytes * 8 && cur.endpoint != 0) {
                found.push_back(cur);
            }
        }
        haveFormat = false;
    };

    bool configSeen = false;
    bool stop = false;
    size_t pos = 0;
    while (!stop && pos + 2 <= length) {
        const uint8_t len = data[pos];
        const uint8_t type = data[pos + 1];
        if (len < 2 || pos + len > length) break;
        const uint8_t *d = data + pos;

        switch (type) {
            case kDescConfiguration:
                if (configSeen) {
                    stop = true;  // only the first configuration is of interest
                } else {
                    configSeen = true;
                }
                break;

            case kDescInterface:
                if (len < 9) break;
                finish();
                ifaceClass = d[5];
                ifaceSub = d[6];
                ifaceProto = d[7];
                inStreaming = false;
                if (ifaceClass == kClassAudio && ifaceSub == kSubControl) {
                    if (caps->controlInterface < 0) caps->controlInterface = d[2];
                    if (ifaceProto == 0x20) acVersion = 2;
                } else if (ifaceClass == kClassAudio && ifaceSub == kSubStreaming) {
                    inStreaming = true;
                    if (d[3] > 0) {  // alt setting 0 has no endpoints: it is the "off" setting
                        cur = StreamFormat{};
                        cur.interfaceNumber = d[2];
                        cur.altSetting = d[3];
                        cur.uac = (ifaceProto == 0x20) ? 2 : 1;
                        haveFormat = true;
                        capture = false;
                        sawEndpoint = false;
                        formatPcm = true;
                    }
                }
                break;

            case kDescCsInterface:
                if (len < 3) break;
                if (ifaceClass == kClassAudio && ifaceSub == kSubControl) {
                    const int subtype = d[2];
                    if (subtype == kAcHeader && len >= 5) {
                        acVersion = (u16(d + 3) >= 0x0200) ? 2 : 1;
                    } else if (subtype == kAcInputTerminal && acVersion == 2 && len >= 8) {
                        terminalClock[d[3]] = d[7];
                    } else if (subtype == kAcClockSource && len >= 4) {
                        clocks[d[3]].kind = kAcClockSource;
                        if (firstClockSource == 0) firstClockSource = d[3];
                    } else if (subtype == kAcClockSelector && len >= 6) {
                        ClockEntity &e = clocks[d[3]];
                        e.kind = kAcClockSelector;
                        for (int i = 0; i < d[4] && 5 + i < len; i++) e.pins.push_back(d[5 + i]);
                    } else if (subtype == kAcClockMultiplier && len >= 5) {
                        ClockEntity &e = clocks[d[3]];
                        e.kind = kAcClockMultiplier;
                        e.source = d[4];
                    }
                } else if (inStreaming && haveFormat) {
                    const int subtype = d[2];
                    if (subtype == 0x01) {  // AS_GENERAL
                        if (cur.uac == 2) {
                            if (len >= 16) {
                                cur.terminalLink = d[3];
                                cur.channels = d[10];
                                formatPcm = (d[5] == 1) && ((u32(d + 6) & 1u) != 0);
                            }
                        } else if (len >= 7) {
                            cur.terminalLink = d[3];
                            formatPcm = (u16(d + 5) == 1);
                        }
                    } else if (subtype == 0x02) {  // FORMAT_TYPE
                        if (cur.uac == 2) {
                            if (len >= 6 && d[3] == 1) {
                                cur.subslotBytes = d[4];
                                cur.bitResolution = d[5];
                            } else {
                                formatPcm = false;
                            }
                        } else if (len >= 8 && d[3] == 1) {
                            cur.channels = d[4];
                            cur.subslotBytes = d[5];
                            cur.bitResolution = d[6];
                            const int n = d[7];
                            if (n == 0) {
                                if (len >= 14) {
                                    cur.rateRanges.push_back(u24(d + 8));
                                    cur.rateRanges.push_back(u24(d + 11));
                                }
                            } else {
                                for (int i = 0; i < n && 8 + 3 * i + 3 <= len; i++) cur.rates.push_back(u24(d + 8 + 3 * i));
                            }
                        } else {
                            formatPcm = false;
                        }
                    }
                }
                break;

            case kDescEndpoint:
                if (len >= 7 && (d[3] & 3) == 1) {  // isochronous only
                    const int addr = d[2];
                    const int attr = d[3];
                    const int wMax = u16(d + 4);
                    const int interval = d[6];
                    const bool in = (addr & 0x80) != 0;
                    const int usage = (attr >> 4) & 3;
                    const int maxPacket = (wMax & 0x7FF) * (1 + ((wMax >> 11) & 3));
                    if (!inStreaming || !haveFormat) {
                        break;
                    }
                    if (!sawEndpoint) {
                        sawEndpoint = true;
                        if (in) {
                            capture = true;
                            if (usage == 2) implicitFeedbackSeen = true;
                            break;
                        }
                        cur.endpoint = addr;
                        cur.maxPacketBytes = maxPacket;
                        cur.interval = interval > 0 ? interval : 1;
                        cur.sync = static_cast<SyncType>((attr >> 2) & 3);
                        if (cur.uac == 1 && len >= 9 && d[8] != 0) {
                            cur.feedbackEndpoint = d[8];  // UAC1: the data endpoint names its sync endpoint
                        }
                    } else if (in && !capture) {
                        // The second endpoint of a playback alt setting: the feedback (UAC1: sync) endpoint.
                        if (usage == 1 || cur.uac == 1) {
                            cur.feedbackEndpoint = addr;
                            cur.feedbackMaxPacketBytes = maxPacket;
                            cur.feedbackInterval = interval > 0 ? interval : 1;
                        }
                    }
                }
                break;

            default:
                break;
        }
        pos += len;
    }
    finish();

    // Which clock does each format run on? (UAC2.)
    auto resolveClock = [&](int id) {
        for (int hops = 0; hops < 8; hops++) {
            auto it = clocks.find(id);
            if (it == clocks.end()) return id;
            const ClockEntity &e = it->second;
            if (e.kind == kAcClockSelector) {
                if (e.pins.empty()) return id;
                id = e.pins[0];
            } else if (e.kind == kAcClockMultiplier) {
                id = e.source;
            } else {
                return id;
            }
        }
        return id;
    };

    caps->uac = acVersion != 0 ? acVersion : (found.empty() ? 0 : found[0].uac);
    for (StreamFormat &f : found) {
        if (f.uac == 2) {
            auto it = terminalClock.find(f.terminalLink);
            f.clockId = resolveClock(it != terminalClock.end() ? it->second : firstClockSource);
        }
        // An asynchronous endpoint with no feedback endpoint, next to a capture endpoint that is
        // "implicit feedback data", gets its feedback from the capture stream: not supported here.
        if (f.sync == SyncType::kAsync && f.feedbackEndpoint == 0 && implicitFeedbackSeen) {
            f.implicitFeedback = true;
        }
        if (!f.implicitFeedback) caps->formats.push_back(f);
    }
    finalizeRates(caps);
    return !caps->formats.empty();
}

void finalizeRates(DeviceCaps *caps) {
    for (StreamFormat &f : caps->formats) {
        for (size_t i = 0; i + 1 < f.rateRanges.size(); i += 2) {
            addStandardRatesIn(f.rateRanges[i], f.rateRanges[i + 1], 0, &f.rates);
        }
        f.rateRanges.clear();
        sortUnique(&f.rates);
    }
}

void queryClockRates(UsbTransport &transport, DeviceCaps *caps) {
    std::map<int, std::vector<int>> byClock;
    for (StreamFormat &f : caps->formats) {
        if (f.uac != 2) continue;
        auto found = byClock.find(f.clockId);
        if (found == byClock.end()) {
            std::vector<int> rates;
            // GET_RANGE of the clock's sampling-frequency control: first how many sub-ranges, then all.
            const uint16_t index = static_cast<uint16_t>((f.clockId << 8) | (caps->controlInterface & 0xFF));
            uint8_t buf[2 + 12 * 64] = {};
            int n = transport.control(0xA1, 0x02, 0x0100, index, buf, 2, 500);
            int total = 0;
            if (n == 2) {
                const int subRanges = std::min(u16(buf), 64);
                total = 2 + 12 * subRanges;
                n = transport.control(0xA1, 0x02, 0x0100, index, buf, static_cast<uint16_t>(total), 500);
            } else {
                n = transport.control(0xA1, 0x02, 0x0100, index, buf, sizeof(buf), 500);
            }
            if (n >= 2) {
                const int subRanges = std::min(u16(buf), 64);
                for (int i = 0; i < subRanges && 2 + 12 * (i + 1) <= n; i++) {
                    const uint8_t *r = buf + 2 + 12 * i;
                    const uint32_t lo = u32(r), hi = u32(r + 4), res = u32(r + 8);
                    if (lo == 0 || hi < lo) continue;
                    if (lo == hi) {
                        rates.push_back(static_cast<int>(lo));
                    } else {
                        addStandardRatesIn(static_cast<int>(lo), static_cast<int>(hi), static_cast<int>(res), &rates);
                    }
                }
            }
            sortUnique(&rates);
            found = byClock.emplace(f.clockId, std::move(rates)).first;
        }
        f.rates = found->second;
    }
    finalizeRates(caps);
}

const StreamFormat *chooseFormat(const DeviceCaps &caps, int rate, int channels, int sourceBits,
                                 bool *duplicateMono) {
    if (duplicateMono != nullptr) *duplicateMono = false;
    const int wanted = sourceBits > 0 ? std::min(sourceBits, 32) : 24;

    struct Rank {
        int exactChannels, fits, closeness, smallSlot, lowAlt;
        bool operator>(const Rank &o) const {
            if (exactChannels != o.exactChannels) return exactChannels > o.exactChannels;
            if (fits != o.fits) return fits > o.fits;
            if (closeness != o.closeness) return closeness > o.closeness;
            if (smallSlot != o.smallSlot) return smallSlot > o.smallSlot;
            return lowAlt > o.lowAlt;
        }
    };

    const StreamFormat *best = nullptr;
    Rank bestRank{};
    bool bestDup = false;
    for (const StreamFormat &f : caps.formats) {
        if (!std::binary_search(f.rates.begin(), f.rates.end(), rate)) continue;
        bool dup = false;
        if (f.channels != channels) {
            if (channels == 1 && f.channels == 2) {
                dup = true;
            } else {
                continue;
            }
        }
        Rank rank;
        rank.exactChannels = dup ? 0 : 1;
        rank.fits = f.bitResolution >= wanted ? 1 : 0;
        // Holds the music: as little room to spare as possible. Does not: as many bits as there are.
        rank.closeness = f.bitResolution >= wanted ? -(f.bitResolution - wanted) : f.bitResolution;
        rank.smallSlot = -f.subslotBytes;
        rank.lowAlt = -f.altSetting;
        if (best == nullptr || rank > bestRank) {
            best = &f;
            bestRank = rank;
            bestDup = dup;
        }
    }
    if (duplicateMono != nullptr) *duplicateMono = bestDup;
    return best;
}

int nearestSupportedRate(const DeviceCaps &caps, int wanted) {
    const std::vector<int> rates = caps.rates();
    if (rates.empty() || wanted <= 0) return 0;
    if (std::binary_search(rates.begin(), rates.end(), wanted)) return wanted;
    for (int r : rates) {
        if (r > wanted && r % wanted == 0) return r;  // 44.1 -> 88.2: an integer multiple keeps it clean
    }
    for (int r : rates) {
        if (r > wanted) return r;
    }
    return rates.back();
}

std::string describe(const DeviceCaps &caps) {
    if (caps.formats.empty()) return "no PCM playback stream";
    const std::vector<int> rates = caps.rates();
    std::set<int> bits;
    for (const StreamFormat &f : caps.formats) bits.insert(f.bitResolution);
    std::string out = "UAC" + std::to_string(caps.uac) + " · ";
    if (!rates.empty()) {
        out += rateText(rates.front());
        if (rates.size() > 1) out += " – " + rateText(rates.back());
        out += " · ";
    }
    bool first = true;
    for (int b : bits) {
        out += (first ? "" : "/") + std::to_string(b);
        first = false;
    }
    out += "-bit";
    return out;
}

}  // namespace resonix::usb
