#include "UsbfsTransport.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "ResonixUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace resonix::usb {

UsbfsTransport::UsbfsTransport(int fd) : mFd(fd) {}

UsbfsTransport::~UsbfsTransport() {
    // Hand every claimed interface back (and with it the kernel driver) before letting go of the device.
    const std::set<int> claimed = mClaimed;
    for (int n : claimed) releaseInterface(n);
    if (mFd >= 0) ::close(mFd);
}

int UsbfsTransport::claimInterface(int interfaceNumber) {
    usbdevfs_disconnect_claim dc;
    std::memset(&dc, 0, sizeof(dc));
    dc.interface = static_cast<unsigned int>(interfaceNumber);
    dc.flags = 0;  // detach whatever driver holds it, then claim
    int rc = ioctl(mFd, USBDEVFS_DISCONNECT_CLAIM, &dc);
    if (rc < 0 && errno == ENOTTY) {
        // An old kernel without the combined call: detach, then claim.
        usbdevfs_ioctl cmd;
        std::memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = interfaceNumber;
        cmd.ioctl_code = USBDEVFS_DISCONNECT;
        ioctl(mFd, USBDEVFS_IOCTL, &cmd);  // ENODATA when nothing was bound: fine
        unsigned int n = static_cast<unsigned int>(interfaceNumber);
        rc = ioctl(mFd, USBDEVFS_CLAIMINTERFACE, &n);
    }
    if (rc < 0) {
        const int e = errno;
        LOGW("Claiming interface %d failed: %s", interfaceNumber, std::strerror(e));
        return -e;
    }
    mClaimed.insert(interfaceNumber);
    return 0;
}

void UsbfsTransport::releaseInterface(int interfaceNumber) {
    if (mClaimed.erase(interfaceNumber) == 0) return;
    unsigned int n = static_cast<unsigned int>(interfaceNumber);
    ioctl(mFd, USBDEVFS_RELEASEINTERFACE, &n);
    // Let the kernel's audio driver take the interface back, so the DAC works for Android again.
    usbdevfs_ioctl cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = interfaceNumber;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    ioctl(mFd, USBDEVFS_IOCTL, &cmd);
}

int UsbfsTransport::setAltSetting(int interfaceNumber, int altSetting) {
    usbdevfs_setinterface si;
    si.interface = static_cast<unsigned int>(interfaceNumber);
    si.altsetting = static_cast<unsigned int>(altSetting);
    if (ioctl(mFd, USBDEVFS_SETINTERFACE, &si) < 0) {
        const int e = errno;
        LOGW("Selecting alt setting %d of interface %d failed: %s", altSetting, interfaceNumber, std::strerror(e));
        return -e;
    }
    return 0;
}

int UsbfsTransport::control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index, void *data,
                            uint16_t length, unsigned timeoutMs) {
    usbdevfs_ctrltransfer ct;
    std::memset(&ct, 0, sizeof(ct));
    ct.bRequestType = requestType;
    ct.bRequest = request;
    ct.wValue = value;
    ct.wIndex = index;
    ct.wLength = length;
    ct.timeout = timeoutMs;
    ct.data = data;
    const int rc = ioctl(mFd, USBDEVFS_CONTROL, &ct);
    return rc < 0 ? -errno : rc;
}

int UsbfsTransport::speed() {
    const int rc = ioctl(mFd, USBDEVFS_GET_SPEED, 0);
    return rc < 0 ? 0 : rc;
}

int UsbfsTransport::submit(IsoTransfer *t) {
    const size_t n = t->packets.size();
    const size_t size = sizeof(usbdevfs_urb) + n * sizeof(usbdevfs_iso_packet_desc);
    if (!t->kernel) {
        void *block = std::calloc(1, size);
        if (block == nullptr) return -ENOMEM;
        t->kernel = std::shared_ptr<void>(block, std::free);
    }
    auto *urb = static_cast<usbdevfs_urb *>(t->kernel.get());
    std::memset(urb, 0, size);
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = t->endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = t->buffer.data();
    int total = 0;
    for (size_t i = 0; i < n; i++) {
        urb->iso_frame_desc[i].length = t->packets[i].length;
        total += static_cast<int>(t->packets[i].length);
    }
    urb->buffer_length = total;
    urb->number_of_packets = static_cast<int>(n);
    urb->usercontext = t;
    if (ioctl(mFd, USBDEVFS_SUBMITURB, urb) < 0) return -errno;
    return 0;
}

IsoTransfer *UsbfsTransport::reap(int timeoutMs, int *error) {
    *error = 0;
    pollfd pfd;
    pfd.fd = mFd;
    pfd.events = POLLOUT;  // usbfs reports finished transfers as "writable"
    pfd.revents = 0;
    const int pr = poll(&pfd, 1, timeoutMs);
    if (pr < 0) {
        if (errno == EINTR) return nullptr;
        *error = -errno;
        return nullptr;
    }
    if (pr == 0) return nullptr;

    void *done = nullptr;
    if (ioctl(mFd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
        if (errno == EAGAIN) {
            // Nothing completed. If the device has been unplugged, poll() keeps reporting an error.
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) *error = -ENODEV;
            return nullptr;
        }
        *error = -errno;
        return nullptr;
    }
    auto *urb = static_cast<usbdevfs_urb *>(done);
    auto *t = static_cast<IsoTransfer *>(urb->usercontext);
    t->status = urb->status;
    for (size_t i = 0; i < t->packets.size(); i++) {
        t->packets[i].actualLength = urb->iso_frame_desc[i].actual_length;
        t->packets[i].status = static_cast<int32_t>(urb->iso_frame_desc[i].status);
    }
    return t;
}

void UsbfsTransport::discard(IsoTransfer *t) {
    if (t->kernel) ioctl(mFd, USBDEVFS_DISCARDURB, t->kernel.get());
}

}  // namespace resonix::usb
