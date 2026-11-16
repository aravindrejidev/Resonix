#pragma once
// The few things the direct USB audio driver needs from a USB device, as an interface: the real
// implementation (UsbfsTransport) talks to the kernel through the usbfs file descriptor Android's
// UsbManager hands out; the tests plug in a simulated DAC instead.

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace resonix::usb {

struct IsoPacket {
    uint32_t length = 0;        // bytes to send / room to receive
    uint32_t actualLength = 0;  // bytes really transferred (set on completion)
    int32_t status = 0;         // 0 = ok, otherwise a negative errno
};

// One isochronous transfer: a run of packets, each occupying one (micro)frame slot.
struct IsoTransfer {
    uint8_t endpoint = 0;            // with the direction bit (0x80 = IN)
    std::vector<uint8_t> buffer;     // OUT: audio to send; IN: where the data lands
    std::vector<IsoPacket> packets;
    int status = 0;                  // whole-transfer result on completion: 0 or a negative errno
    int tag = 0;                     // the owner's own bookkeeping
    std::shared_ptr<void> kernel;    // private to the transport; freed together with the transfer
};

class UsbTransport {
public:
    virtual ~UsbTransport() = default;

    // Claims an interface for this process, detaching the kernel driver (snd-usb-audio) from it.
    virtual int claimInterface(int interfaceNumber) = 0;
    // Gives the interface back and lets the kernel driver bind again. Safe to call when not claimed.
    virtual void releaseInterface(int interfaceNumber) = 0;
    virtual int setAltSetting(int interfaceNumber, int altSetting) = 0;

    // Control transfer on endpoint 0. Returns the bytes transferred, or a negative errno.
    virtual int control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                        void *data, uint16_t length, unsigned timeoutMs) = 0;

    // 1 low, 2 full, 3 high, 5 super speed; 0 when unknown.
    virtual int speed() = 0;

    // Queues the transfer (it is scheduled right after whatever is already queued on the endpoint).
    // 0 or a negative errno.
    virtual int submit(IsoTransfer *transfer) = 0;
    // Waits up to [timeoutMs] for any submitted transfer to complete. nullptr with *error == 0 on
    // timeout; nullptr with a negative errno when the device is gone.
    virtual IsoTransfer *reap(int timeoutMs, int *error) = 0;
    // Cancels a submitted transfer; it will still be returned by reap() (with a negative status).
    virtual void discard(IsoTransfer *transfer) = 0;
};

}  // namespace resonix::usb
