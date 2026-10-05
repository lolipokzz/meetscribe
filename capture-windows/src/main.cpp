#define NOMINMAX
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <ks.h>
#include <ksmedia.h>
#include "wave.hpp"
#include "audio.hpp"
#include <functiondiscoverykeys_devpkey.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
std::atomic_bool stopping{false};
std::atomic_bool invalidCommand{false};
BOOL WINAPI consoleControl(DWORD signal) {
    if (signal == CTRL_C_EVENT || signal == CTRL_BREAK_EVENT) { stopping = true; return TRUE; }
    return FALSE;
}
std::string escape(const std::string& s) {
    std::string out;
    for (unsigned char c : s) {
        if (c == '\\' || c == '"') { out += '\\'; out += c; }
        else if (c < 32) out += ' ';
        else out += c;
    }
    return out;
}
void event(const std::string& type, const std::string& fields = "") {
    std::cout << "{\"protocolVersion\":1,\"event\":\"" << type << "\"" << fields << "}\n" << std::flush;
}
void check(HRESULT hr, const char* action) {
    if (FAILED(hr)) {
        std::ostringstream s;
        s << action << " failed (HRESULT 0x" << std::hex << static_cast<unsigned long>(hr)
          << "). Check device connection, microphone privacy permission, and Windows Audio service.";
        throw std::runtime_error(s.str());
    }
}
template<class T> struct Com {
    T* p = nullptr;
    ~Com() { if (p) p->Release(); }
    void reset() { if (p) p->Release(); p = nullptr; }
    T** put() { reset(); return &p; }
    T* operator->() { return p; }
    Com() = default;
    Com(const Com&) = delete;
};
uint64_t clock100ns() {
    LARGE_INTEGER t, f;
    QueryPerformanceCounter(&t); QueryPerformanceFrequency(&f);
    return static_cast<uint64_t>((static_cast<long double>(t.QuadPart) * 10000000) / f.QuadPart);
}

std::string utf8(const std::wstring& value) {
    if (value.empty()) return "";
    const int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(count, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count, nullptr, nullptr);
    return result;
}
std::wstring deviceId(IMMDevice* device) {
    LPWSTR value = nullptr; check(device->GetId(&value), "GetId");
    std::wstring result(value); CoTaskMemFree(value); return result;
}
void listDevices(IMMDeviceEnumerator* enumerator) {
    std::cout << "{\"protocolVersion\":1,\"devices\":[";
    bool first = true;
    for (auto flow : {eCapture, eRender}) {
        Com<IMMDevice> defaultDevice;
        std::wstring defaultId;
        if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(flow, eConsole, defaultDevice.put()))) defaultId = deviceId(defaultDevice.p);
        Com<IMMDeviceCollection> collection;
        check(enumerator->EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, collection.put()), "EnumAudioEndpoints");
        UINT count = 0; check(collection->GetCount(&count), "GetCount");
        for (UINT i = 0; i < count; ++i) {
            Com<IMMDevice> device; check(collection->Item(i, device.put()), "Item");
            const auto id = deviceId(device.p);
            std::wstring name = id;
            Com<IPropertyStore> properties;
            if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, properties.put()))) {
                PROPVARIANT value; PropVariantInit(&value);
                if (SUCCEEDED(properties->GetValue(PKEY_Device_FriendlyName, &value)) && value.vt == VT_LPWSTR) name = value.pwszVal;
                PropVariantClear(&value);
            }
            if (!first) std::cout << ',';
            first = false;
            std::cout << "{\"id\":\"" << escape(utf8(id)) << "\",\"name\":\"" << escape(utf8(name))
                      << "\",\"flow\":\"" << (flow == eCapture ? "input" : "output")
                      << "\",\"isDefault\":" << (id == defaultId ? "true" : "false") << '}';
        }
    }
    std::cout << "]}\n" << std::flush;
}
class DeviceUnavailable : public std::runtime_error { public: using std::runtime_error::runtime_error; };
class Stream {
    IMMDeviceEnumerator* enumerator;
    EDataFlow flow;
    std::wstring requested, activeId;
    std::string track;
    Com<IMMDevice> device;
    Com<IAudioClient> client;
    Com<IAudioCaptureClient> capture;
    WAVEFORMATEX* format = nullptr;
    std::unique_ptr<Wave> wave;
    MonoResampler resampler;
    bool floating = false, started = false, reconnecting = false;
    uint64_t retryAt = 0, failedAt = 0, checkAt = 0;
    void close() {
        if (started && client.p) client->Stop();
        started = false; capture.reset(); client.reset(); device.reset();
        if (format) CoTaskMemFree(format);
        format = nullptr; resampler.reset();
    }
    void connect() {
        close();
        if (requested == L"default") check(enumerator->GetDefaultAudioEndpoint(flow, eConsole, device.put()), "GetDefaultAudioEndpoint");
        else check(enumerator->GetDevice(requested.c_str(), device.put()), "GetDevice");
        DWORD state = 0; check(device->GetState(&state), "GetState");
        if (!(state & DEVICE_STATE_ACTIVE)) throw std::runtime_error("Selected endpoint is disconnected or disabled");
        activeId = deviceId(device.p);
        check(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(client.put())), "Activate");
        check(client->GetMixFormat(&format), "GetMixFormat");
        floating = format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT;
        if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE && format->cbSize >= 22) {
            auto* ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(format);
            floating = IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
            if (!floating && !IsEqualGUID(ext->SubFormat, KSDATAFORMAT_SUBTYPE_PCM)) throw std::runtime_error("Unsupported audio subtype");
        } else if (!floating && format->wFormatTag != WAVE_FORMAT_PCM) throw std::runtime_error("Unsupported audio format");
        if (!format->nSamplesPerSec || !format->nChannels || (floating && format->wBitsPerSample != 32) ||
            (!floating && format->wBitsPerSample != 16 && format->wBitsPerSample != 24 && format->wBitsPerSample != 32))
            throw std::runtime_error("Unsupported audio sample width/rate/channels");
        check(client->Initialize(AUDCLNT_SHAREMODE_SHARED, flow == eRender ? AUDCLNT_STREAMFLAGS_LOOPBACK : 0,
                                1000000, 0, format, nullptr), "Initialize");
        check(client->GetService(__uuidof(IAudioCaptureClient), reinterpret_cast<void**>(capture.put())), "GetService");
    }
    void changed(const std::string& status, const std::string& message) {
        event("deviceChanged", ",\"track\":\"" + track + "\",\"status\":\"" + status + "\",\"deviceId\":\"" +
              escape(utf8(activeId)) + "\",\"message\":\"" + escape(message) + "\"");
    }
    void lost(uint64_t now, const std::string& message) {
        close();
        if (!reconnecting) { failedAt = now; reconnecting = true; changed("reconnecting", message); }
        retryAt = now + 5000000;
    }
    void drain(uint64_t origin) {
        UINT32 available = 0;
        check(capture->GetNextPacketSize(&available), "GetNextPacketSize");
        while (available) {
            BYTE* data = nullptr; UINT32 frames = 0; DWORD flags = 0; UINT64 qpc = 0;
            check(capture->GetBuffer(&data, &frames, &flags, nullptr, &qpc), "GetBuffer");
            try {
                const uint64_t target = (flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR) ? wave->size() : frameAt(qpc, origin);
                const auto converted = resampler.convert(data, frames, format->nSamplesPerSec, format->nChannels,
                    format->wBitsPerSample, floating, flags & AUDCLNT_BUFFERFLAGS_SILENT, peak);
                appendAligned(*wave, target, converted);
            } catch (...) { capture->ReleaseBuffer(frames); throw; }
            check(capture->ReleaseBuffer(frames), "ReleaseBuffer");
            check(capture->GetNextPacketSize(&available), "GetNextPacketSize");
        }
    }
public:
    double peak = 0;
    Stream(IMMDeviceEnumerator* e, EDataFlow f, const std::wstring& id, const std::filesystem::path& path)
        : enumerator(e), flow(f), requested(id), track(f == eCapture ? "mic" : "system") {
        try { connect(); } catch (const std::exception& ex) { close(); throw DeviceUnavailable(track + ": " + ex.what()); }
        try { wave = std::make_unique<Wave>(path, MonoResampler::rate, 1); }
        catch (...) { close(); throw; }
    }
    ~Stream() { close(); }
    void start() { check(client->Start(), "Start"); started = true; }
    void tick(uint64_t origin, uint64_t now) {
        if (reconnecting) {
            if (now - failedAt >= 300000000) throw DeviceUnavailable(track + ": audio device unavailable for 30 seconds; reconnect it and start a new recording");
            if (now < retryAt) return;
            try { connect(); start(); reconnecting = false; changed("connected", "Audio endpoint restored; interruption was padded with silence"); }
            catch (const std::exception& e) { lost(now, e.what()); return; }
        }
        if (now >= checkAt) {
            checkAt = now + 5000000;
            DWORD state = 0;
            if (FAILED(device->GetState(&state)) || !(state & DEVICE_STATE_ACTIVE)) {
                lost(now, "Selected endpoint disconnected or disabled"); return;
            }
            if (requested == L"default") {
                Com<IMMDevice> current;
                if (FAILED(enumerator->GetDefaultAudioEndpoint(flow, eConsole, current.put())) || deviceId(current.p) != activeId) {
                    lost(now, "Default endpoint changed or became unavailable"); return;
                }
            }
        }
        try { drain(origin); }
        catch (const std::exception& e) {
            // Disk errors must terminate, rather than masquerade as endpoint reconnection.
            const std::string message(e.what());
            if (message.find("HRESULT") == std::string::npos) throw;
            lost(now, message);
        }
    }
    void fill(uint64_t origin, uint64_t until) { wave->silenceUntil(frameAt(until, origin)); }
    void checkpoint() { wave->checkpoint(); }
    void finish(uint64_t origin, uint64_t end) {
        if (started) { client->Stop(); started = false; try { drain(origin); } catch (const std::exception& e) {
            if (std::string(e.what()).find("HRESULT") == std::string::npos) throw;
        } }
        fill(origin, end); checkpoint();
    }
};
void commands() {
    // Full match prevents malformed/unsupported commands from being silently accepted.
    const std::regex stopCommand(R"(^\s*\{\s*("protocolVersion"\s*:\s*1\s*,\s*"command"\s*:\s*"stop"|"command"\s*:\s*"stop"\s*,\s*"protocolVersion"\s*:\s*1)\s*\}\s*$)");
    std::string line;
    while (std::getline(std::cin, line)) {
        if (std::regex_match(line, stopCommand)) { stopping = true; return; }
        invalidCommand = true;
    }
    stopping = true; // Parent crash/closed pipe safely finalizes files.
}
}
int wmain(int argc, wchar_t* argv[]) {
    if (argc == 2 && std::wstring(argv[1]) == L"--help") {
        std::cout << "Usage: meetscribe-capture --directory PATH [--microphone default|ID] [--output-device default|ID]\n"
                     "       meetscribe-capture --list-devices\n"
                     "Writes mic.wav and system.wav. stdin JSONL: {\"protocolVersion\":1,\"command\":\"stop\"}\n";
        return 0;
    }
    bool comInitialized = false;
    const bool listing = argc == 2 && std::wstring(argv[1]) == L"--list-devices";
    try {
        std::filesystem::path directory;
        std::wstring microphone = L"default", output = L"default";
        for (int i = 1; !listing && i < argc; i += 2) {
            if (i + 1 >= argc) throw std::runtime_error("Missing option value; use --help");
            const std::wstring arg = argv[i];
            if (arg == L"--directory") directory = argv[i + 1];
            else if (arg == L"--microphone") microphone = argv[i + 1];
            else if (arg == L"--output-device") output = argv[i + 1];
            else throw std::runtime_error("Unknown option; use --help");
        }
        if (!listing && directory.empty()) throw std::runtime_error("--directory is required");
        if (!listing) std::filesystem::create_directories(directory);
        check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "CoInitializeEx"); comInitialized = true;
        {
            Com<IMMDeviceEnumerator> devices;
            check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                   __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(devices.put())), "CoCreateInstance");
            if (listing) { listDevices(devices.p); } else {
            Stream mic(devices.p, eCapture, microphone, directory / L"mic.wav");
            Stream system(devices.p, eRender, output, directory / L"system.wav");
            event("ready");
            const uint64_t origin = clock100ns();
            mic.start(); system.start();
            event("started");
            SetConsoleCtrlHandler(consoleControl, TRUE);
            std::thread(commands).detach();
            uint64_t lastLevel = origin, lastCheckpoint = origin;
            while (!stopping) {
                const auto now = clock100ns();
                mic.tick(origin, now); system.tick(origin, now);
                if (now - origin >= 144000000000ULL) { stopping = true; event("limitReached", ",\"seconds\":14400"); }
                // A 200 ms holdback lets normal engine packets arrive before committing silence.
                if (now > origin + 2000000) { mic.fill(origin, now - 2000000); system.fill(origin, now - 2000000); }
                if (now - lastLevel >= 1000000) {
                    event("levels", ",\"mic\":" + std::to_string(mic.peak) + ",\"system\":" + std::to_string(system.peak));
                    mic.peak = system.peak = 0; lastLevel = now;
                }
                if (now - lastCheckpoint >= 10000000) { mic.checkpoint(); system.checkpoint(); lastCheckpoint = now; }
                if (invalidCommand.exchange(false)) event("error", ",\"code\":\"INVALID_COMMAND\",\"fatal\":false,\"message\":\"Expected protocolVersion 1 stop command\"");
                Sleep(10);
            }
            const auto end = clock100ns();
            mic.finish(origin, end); system.finish(origin, end);
            event("stopped");
            }
        }
        CoUninitialize();
        return 0;
    } catch (const std::exception& e) {
        event("error", ",\"code\":\"" + std::string(dynamic_cast<const DeviceUnavailable*>(&e) ? "DEVICE_UNAVAILABLE" : "CAPTURE_FAILED") + "\",\"fatal\":true,\"message\":\"" + escape(e.what()) + "\"");
        if (comInitialized) CoUninitialize();
        return 2;
    }
}
