#pragma once
#include "wave.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

// Area averaging with persistent fractional phase across packets. Always mono 48 kHz.
class MonoResampler {
    double remaining = 1.0, sum = 0;
public:
    static constexpr uint32_t rate = 48000;
    void reset() { remaining = 1; sum = 0; }
    std::vector<int16_t> convert(const unsigned char* data, uint32_t frames, uint32_t inputRate,
                                 uint16_t channels, uint16_t bits, bool floating, bool silent,
                                 double& peak) {
        if (!inputRate || !channels || (bits != 16 && bits != 24 && bits != 32) || (floating && bits != 32))
            throw std::runtime_error("Unsupported audio format");
        std::vector<int16_t> result;
        result.reserve(static_cast<size_t>(frames * (double(rate) / inputRate) + 2));
        for (uint32_t f = 0; f < frames; ++f) {
            double mono = 0;
            for (uint16_t c = 0; c < channels; ++c) {
                double sample = 0;
                if (!silent) {
                    const auto* p = data + (size_t(f) * channels + c) * (bits / 8);
                    if (floating) { float v; std::memcpy(&v, p, 4); sample = std::isfinite(v) ? v : 0; }
                    else if (bits == 16) { int16_t v; std::memcpy(&v, p, 2); sample = v / 32768.0; }
                    else if (bits == 32) { int32_t v; std::memcpy(&v, p, 4); sample = v / 2147483648.0; }
                    else { int32_t v = p[0] | (p[1] << 8) | (p[2] << 16); if (v & 0x800000) v -= 0x1000000; sample = v / 8388608.0; }
                }
                mono += std::clamp(sample, -1.0, 1.0) / channels;
            }
            peak = std::max(peak, std::abs(mono));
            double width = double(rate) / inputRate;
            while (width > 1e-10) {
                const double used = std::min(width, remaining);
                sum += mono * used; remaining -= used; width -= used;
                if (remaining < 1e-10) {
                    result.push_back(static_cast<int16_t>(std::clamp(std::round(sum * 32768), -32768.0, 32767.0)));
                    remaining = 1; sum = 0;
                }
            }
        }
        return result;
    }
};
inline uint64_t frameAt(uint64_t timestamp, uint64_t origin) {
    return timestamp <= origin ? 0 : static_cast<uint64_t>((static_cast<long double>(timestamp - origin) * MonoResampler::rate) / 10000000);
}
inline void appendAligned(Wave& wave, uint64_t target, const std::vector<int16_t>& samples) {
    wave.silenceUntil(target);
    const auto skip = std::min<uint64_t>(samples.size(), wave.size() - target);
    if (skip < samples.size()) wave.write(samples.data() + skip, samples.size() - skip);
}
