#pragma once
#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <vector>

class Wave {
    std::fstream file;
    uint32_t rate;
    uint16_t channels;
    uint64_t frames = 0;
    void u16(uint16_t v) { char b[2]{static_cast<char>(v), static_cast<char>(v >> 8)}; file.write(b, 2); }
    void u32(uint32_t v) { u16(static_cast<uint16_t>(v)); u16(static_cast<uint16_t>(v >> 16)); }
public:
    Wave(const std::filesystem::path& path, uint32_t r, uint16_t c) : rate(r), channels(c) {
        if (std::filesystem::exists(path)) throw std::runtime_error("Refusing to overwrite existing WAV file");
        file.open(path, std::ios::binary | std::ios::out | std::ios::in | std::ios::trunc);
        if (!file) throw std::runtime_error("Cannot create WAV file in meeting directory");
        checkpoint();
    }
    ~Wave() { try { checkpoint(); } catch (...) {} }
    uint64_t size() const { return frames; }
    void checkpoint() {
        const auto bytes = static_cast<uint32_t>(frames * channels * 2);
        file.seekp(0); file.write("RIFF",4); u32(36 + bytes); file.write("WAVEfmt ",8);
        u32(16); u16(1); u16(channels); u32(rate); u32(rate * channels * 2);
        u16(static_cast<uint16_t>(channels * 2)); u16(16); file.write("data",4); u32(bytes);
        file.seekp(44 + static_cast<std::streamoff>(bytes)); file.flush();
        if (!file) throw std::runtime_error("Cannot checkpoint WAV; disk may be full");
    }
    void write(const int16_t* data, uint64_t count) {
        if ((frames + count) * channels * 2 > 0xfffffff0ULL - 44)
            throw std::runtime_error("WAV reached 4 GiB RIFF limit; start a new meeting");
        file.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(count * channels * 2));
        if (!file) throw std::runtime_error("Cannot write audio; disk may be full");
        frames += count;
    }
    void silenceUntil(uint64_t target) {
        const std::vector<int16_t> zeros(static_cast<size_t>(channels) * 4096, 0);
        while (frames < target) write(zeros.data(), std::min<uint64_t>(4096, target - frames));
    }
};
