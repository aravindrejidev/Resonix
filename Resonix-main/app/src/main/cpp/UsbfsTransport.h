#pragma once
// UsbTransport over the usbfs file descriptor of an Android UsbDeviceConnection
// (UsbDeviceConnection.getFileDescriptor()): the same ioctls libusb uses.

#include <mutex>
#include <set>

#include "UsbTransport.h"

namespace resonix::usb {

class UsbfsTransport : public UsbTransport {
public:
    // Takes over [fd] (the caller passes a dup() of the connection's descriptor).
    explicit UsbfsTransport(int fd);
    ~UsbfsTransport() override;

    int claimInterface(int interfaceNumber) override;
    void releaseInterface(int interfaceNumber) override;
    int setAltSetting(int interfaceNumber, int altSetting) override;
    int control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index, void *data,
                uint16_t length, unsigned timeoutMs) override;
    int speed() override;
    int submit(IsoTransfer *transfer) override;
    IsoTransfer *reap(int timeoutMs, int *error) override;
    void discard(IsoTransfer *transfer) override;

private:
    int mFd;
    std::set<int> mClaimed;
};

}  // namespace resonix::usb
