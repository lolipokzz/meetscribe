#include "../src/audio.hpp"
#include <chrono>
#include <iostream>
#include <limits>
static void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
int main() {
    try {
        for (uint32_t rate : {16000, 44100, 48000, 96000}) {
            std::vector<float> source(rate * 2);
            for (size_t i = 0; i < rate; ++i) { source[2*i] = static_cast<float>(std::sin(i / 40.0)); source[2*i+1] = source[2*i] * .5f; }
            MonoResampler whole, chunks;
            double peak = 0;
            auto expected = whole.convert(reinterpret_cast<unsigned char*>(source.data()), rate, rate, 2, 32, true, false, peak);
            require(expected.size() == 48000, "one second output length across native rates");
            std::vector<int16_t> result;
            for (uint32_t offset = 0; offset < rate; offset += 137) {
                auto part = chunks.convert(reinterpret_cast<unsigned char*>(source.data() + offset * 2), std::min(137u, rate-offset), rate, 2, 32, true, false, peak);
                result.insert(result.end(), part.begin(), part.end());
            }
            require(result == expected, "resampling must preserve phase across packet boundaries");
        }
        MonoResampler samples; double peak = 0;
        unsigned char pcm24[]{0,0,0x80, 0xff,0xff,0x7f};
        auto converted = samples.convert(pcm24,2,48000,1,24,false,false,peak);
        require(converted == std::vector<int16_t>({-32768,32767}), "signed PCM24 extremes");
        float odd[]{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),2,-2};
        converted = samples.convert(reinterpret_cast<unsigned char*>(odd),4,48000,1,32,true,false,peak);
        require(converted == std::vector<int16_t>({0,0,32767,-32768}), "invalid float samples and saturation");
        require(samples.convert(nullptr, 480, 48000, 2, 32, true, true, peak) == std::vector<int16_t>(480,0), "null silent engine packet");
        require(frameAt(999,1000) == 0 && frameAt(10001000,1000) == 48000, "common monotonic clock origin");
        const auto path = std::filesystem::temp_directory_path() / ("meetscribe-timeline-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + ".wav");
        {
            Wave wave(path,48000,1);
            appendAligned(wave,0,std::vector<int16_t>(480,1000));
            appendAligned(wave,48000,std::vector<int16_t>(480,2000)); // reconnect after gap
            appendAligned(wave,47000,std::vector<int16_t>(480,3000)); // late packet
            require(wave.size() == 48480,"reconnect gap and late-packet overlap must preserve clock");
            samples.reset();
            auto changed = samples.convert(nullptr,441,44100,2,16,false,true,peak);
            appendAligned(wave,48480,changed);
            require(wave.size() == 48960,"rate change keeps common output clock");
            wave.checkpoint();
        }
        std::ifstream file(path,std::ios::binary); std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(file)),{});
        require(bytes.size() == 44+48960*2,"fixed mono format length");
        for (size_t i=44+480*2;i<44+48000*2;++i) require(bytes[i]==0,"device gap must contain silence");
        require(bytes[44+48000*2] == 0xd0 && bytes[45+48000*2] == 7,"reconnected audio at expected timestamp");
        file.close(); std::filesystem::remove(path);
        std::cout << "Audio conversion and clock alignment tests passed\n";
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
