#pragma once
// Log lines of the USB driver. They go to logcat as usual and are also kept (the last few hundred) so the app
// can hand them to the user as a "USB report" — a phone has no adb to read logcat with.

#include <android/log.h>

#include <cstdarg>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <chrono>

namespace resonix::usb {

class UsbLog {
public:
    static UsbLog &instance() {
        static UsbLog log;
        return log;
    }

    void add(int priority, const char *text) {
        __android_log_print(priority, "ResonixUsb", "%s", text);
        char prefix[40];
        const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - mStart).count();
        const char level = priority >= ANDROID_LOG_ERROR ? 'E' : (priority >= ANDROID_LOG_WARN ? 'W' : 'I');
        std::snprintf(prefix, sizeof(prefix), "%8.3f %c ", seconds, level);
        std::lock_guard<std::mutex> lock(mMutex);
        mLines.push_back(std::string(prefix) + text);
        while (mLines.size() > kMaxLines) mLines.pop_front();
    }

    std::string dump() {
        std::lock_guard<std::mutex> lock(mMutex);
        std::string out;
        for (const std::string &line : mLines) {
            out += line;
            out += '\n';
        }
        return out;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mMutex);
        mLines.clear();
    }

private:
    static constexpr size_t kMaxLines = 300;
    std::mutex mMutex;
    std::deque<std::string> mLines;
    std::chrono::steady_clock::time_point mStart = std::chrono::steady_clock::now();
};

inline void logLine(int priority, const char *format, ...) {
    char buffer[400];
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    UsbLog::instance().add(priority, buffer);
}

}  // namespace resonix::usb

#define USB_LOGI(...) ::resonix::usb::logLine(ANDROID_LOG_INFO, __VA_ARGS__)
#define USB_LOGW(...) ::resonix::usb::logLine(ANDROID_LOG_WARN, __VA_ARGS__)
#define USB_LOGE(...) ::resonix::usb::logLine(ANDROID_LOG_ERROR, __VA_ARGS__)
