// UsbHidOxHost.h — USB host lifecycle for the PO-400 (VID:PID 28E9:028A),
// implemented directly against ESP-IDF's `usb_host.h` client API (reachable
// under `framework = arduino` since arduino-esp32 is itself built as an IDF
// component — see the plan's fallback clause).
//
// IMPORTANT — why this uses raw usb_host.h instead of EspUsbHost:
// The plan's primary recommendation was to try the community `EspUsbHost`
// Arduino wrapper first. That was attempted here (a full implementation
// against its documented onDeviceConnected/onHIDVendorInput/
// sendHIDVendorOutput API was written and is preserved in git history), but
// EspUsbHost 2.7.7 — the only version available in this environment's
// package registry — fails to *compile* against espressif32@7.0.1 — the
// newest platform version available — with multiple undefined-symbol errors
// in its Mass Storage support (`usb_device_info_t` has no member `parent`,
// `usb_host_get_config_desc`/`usb_host_free_config_desc` don't exist,
// `USB_SPEED_HIGH` isn't declared, `LBA_t`/`esp_vfs_fat_conf_t` are missing).
// These point at EspUsbHost expecting a newer ESP-IDF USB-host surface than
// arduino-esp32's currently-pinnable core bundles. This is exactly the "USB
// host library maturity" risk the plan calls out as the project's single
// biggest unknown, and it manifests even before reaching the OUT-direction
// question the plan's spike was primarily worried about — so this firmware
// takes the plan's explicit fallback path instead: raw `usb_host.h`.
//
// IMPORTANT — hardware-in-the-loop validation still required: this was
// written against usb_host.h's documented "beta" API with no physical PO-400
// or ESP32-S3-USB-OTG board available in this environment. The plan's
// mandatory USB spike (enumerate the device, dump its real endpoint
// descriptors, confirm raw IN reports match the documented byte layout with
// finger in/out, confirm the two start commands get an 0xEB-prefixed ack)
// has NOT been performed. In particular, `discoverHidEndpoints()` below
// auto-detects, from the device's own configuration descriptor, whether it
// exposes a real interrupt-OUT endpoint or only supports OUT via a
// control-transfer HID SET_REPORT — this directly answers the plan's spike
// question in code, but which path a *real* PO-400 actually takes is
// unverified.
#pragma once

#include <Arduino.h>
#include <usb/usb_host.h>

#include <functional>

class UsbHidOxHost {
public:
  UsbHidOxHost();

  // Drives GPIO17/IDEV_LIMIT_EN high to enable the ESP32-S3-USB-OTG board's
  // MIC2005A VBUS power switch (so the host port actually powers/charges the
  // PO-400, not just carries data), installs the USB Host Library, registers
  // a client, and starts the background task that pumps
  // usb_host_lib_handle_events()/usb_host_client_handle_events().
  void begin();

  bool isAttached() const { return attached_; }

  // Blocks up to timeoutMs for the next raw 64-byte INPUT report from the
  // attached device. Returns false on timeout or if no device is attached.
  bool readReport(uint8_t *buf, size_t bufSize, uint32_t timeoutMs);

  // How many completed IN reports inTransferCallback() had to discard
  // because reportQueue_ was full (a burst arriving faster than the current
  // consumer drains it — observed against real hardware right after a
  // stored-record datum-download request, which appears to make the PO-400
  // burst reports faster than the earlier command/response exchanges do).
  // Non-zero here, seen right before a ChecksumError/SequenceError in
  // StoredRecordDecode, is the direct confirmation that a decode "failure"
  // was actually a dropped report, not a real protocol mismatch.
  uint32_t droppedReportCount() const { return droppedReportCount_; }

  // Sends a 64-byte zero-padded OUTPUT report built from the first `length`
  // bytes of `data` (length must be <= 64). Uses the device's real
  // interrupt-OUT endpoint if it has one, otherwise falls back to a
  // control-transfer HID SET_REPORT (Output report, report ID 0) — see the
  // header comment above.
  bool writeReport(const uint8_t *data, size_t length);

  using AttachCallback = std::function<void()>;
  using DetachCallback = std::function<void()>;
  void onAttach(AttachCallback callback) { attachCallback_ = callback; }
  void onDetach(DetachCallback callback) { detachCallback_ = callback; }

  // Fires the same callback a real USB attach event would, without requiring
  // the PO-400 to actually be unplugged and replugged — used by
  // BleGattServer::requestDataDump() so every REQUEST_DATA re-downloads
  // fresh from the still-attached device instead of trusting whatever was
  // already sitting in the relay buffer (the phone dedupes against its own
  // database — see ReadingsRepository.importCsv on the Android side — so
  // this is also what lets a phone recover after its own local copy is
  // lost, e.g. app data cleared, with no separate "resync" action needed).
  // Safe to call even with nothing attached: the resulting
  // downloadAndMaybeDelete() run will simply fail on its first USB
  // exchange, exactly as it would for a real attach callback firing
  // spuriously, and is reported as a failure the same way.
  void triggerAttachCallback() {
    if (attachCallback_) {
      attachCallback_();
    }
  }

private:
  struct ReportMessage {
    uint8_t data[64];
    size_t length;
  };

  static void usbHostTaskTrampoline(void *arg);
  void usbHostTaskLoop();

  static void clientEventCallback(const usb_host_client_event_msg_t *eventMsg,
                                  void *arg);
  void handleNewDevice(uint8_t address);
  void handleDeviceGone(usb_device_handle_t deviceHandle);
  void closeDevice();

  // Walks the device's active configuration descriptor to find the PO-400's
  // interrupt-IN endpoint and, if present, its interrupt-OUT endpoint —
  // answering the plan's spike question ("is there a real interrupt-OUT
  // endpoint, or does OUT have to go via a control-transfer SET_REPORT?")
  // from the descriptor itself rather than assuming either answer.
  bool discoverHidEndpoints();

  static void inTransferCallback(usb_transfer_t *transfer);
  static void outTransferCallback(usb_transfer_t *transfer);
  static void controlTransferCallback(usb_transfer_t *transfer);

  bool submitOutInterruptTransfer(const uint8_t *paddedReport);
  bool submitOutControlTransfer(const uint8_t *paddedReport);

  usb_host_client_handle_t clientHandle_ = nullptr;
  usb_device_handle_t deviceHandle_ = nullptr;
  uint8_t interfaceNumber_ = 0;
  uint8_t inEndpointAddress_ = 0;
  uint8_t outEndpointAddress_ = 0; // 0 if the device has no real OUT endpoint
  bool haveOutEndpoint_ = false;

  usb_transfer_t *inTransfer_ = nullptr;
  usb_transfer_t *outTransfer_ = nullptr; // interrupt OUT or control, either way

  QueueHandle_t reportQueue_ = nullptr;
  volatile uint32_t droppedReportCount_ = 0;
  SemaphoreHandle_t outTransferDoneSignal_ = nullptr;
  volatile bool lastOutTransferOk_ = false;

  volatile bool attached_ = false;
  AttachCallback attachCallback_;
  DetachCallback detachCallback_;
};
