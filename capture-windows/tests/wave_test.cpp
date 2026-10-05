#include "../src/wave.hpp"
#include <array>
#include <chrono>
#include <iostream>

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
static uint32_t read32(const std::vector<unsigned char>& bytes, size_t offset) {
    return bytes.at(offset) | (bytes.at(offset + 1) << 8) | (bytes.at(offset + 2) << 16) | (bytes.at(offset + 3) << 24);
}
int main() {
    const auto root = std::filesystem::temp_directory_path() /
        ("meetscribe-wave-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    std::filesystem::create_directory(root);
    try {
        const auto path = root / "audio.wav";
        {
            Wave wave(path, 48000, 2);
            wave.silenceUntil(48000);
            const std::array<int16_t, 4> samples{1000, -1000, 32767, -32768};
            wave.write(samples.data(), 2);
            wave.checkpoint();
            require(wave.size() == 48002, "frame count");
            wave.silenceUntil(2); // earlier timestamps must not truncate data
            require(wave.size() == 48002, "monotonic timeline");
        }
        std::ifstream file(path, std::ios::binary);
        std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(file)), {});
        require(bytes.size() == 44 + 48002 * 4, "file length");
        require(read32(bytes, 4) == bytes.size() - 8, "RIFF length");
        require(read32(bytes, 24) == 48000, "sample rate");
        require(read32(bytes, 40) == 48002 * 4, "data length");
        for (size_t i = 44; i < 44 + 48000 * 4; ++i) require(bytes[i] == 0, "silence padding");
        require(bytes[44 + 48000 * 4] == 0xe8 && bytes[45 + 48000 * 4] == 0x03, "PCM sample preservation");
        bool refused = false;
        try { Wave duplicate(path, 48000, 2); } catch (const std::runtime_error&) { refused = true; }
        require(refused, "existing audio must not be overwritten");
        file.close();
        std::filesystem::remove_all(root);
        std::cout << "WAV integrity tests passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        std::filesystem::remove_all(root);
        return 1;
    }
}
