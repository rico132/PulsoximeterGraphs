#include "UsbHidOxHost.h"

#include <cstring>

#include "Config.h"

namespace {
// Control transfer buffer layout: 8-byte setup packet followed by up to
// kHidReportSize bytes of report payload — see usb_host_transfer_submit_control()'s
// contract ("the first 8 bytes of the transfer's data buffer should contain
// the setup packet").
constexpr size_t kControlTransferBufferSize = 8 + 64;
constexpr uint8_t kHidRequestSetReport = 0x09;  // HID class request SET_REPORT
constexpr uint8_t kHidReportTypeOutput = 0x02;  // wValue high byte
} // namespace

UsbHidOxHost::UsbHidOxHost() = default;

void UsbHidOxHost::begin() {
#if defined(HAS_USB_HOST_VBUS_SWITCH)
  // Enable the board's MIC2005A VBUS power switch before/alongside bringing
  // up the USB host stack, so the PO-400 is actually powered over the same
  // cable that carries data — without this the host port would carry HID
  // traffic but never charge the device. Only meaningful on boards that
  // actually have this switch (e.g. the ESP32-S3-USB-OTG); on a plain
  // DevKitC-1 build, VBUS is instead supplied externally (e.g. via a USB Y
  // power/data splitter cable) and this GPIO has no wiring behind it.
  pinMode(Config::kUsbHostVbusEnableGpio, OUTPUT);
  digitalWrite(Config::kUsbHostVbusEnableGpio, HIGH);
#endif

  reportQueue_ = xQueueCreate(16, sizeof(ReportMessage));
  outTransferDoneSignal_ = xSemaphoreCreateBinary();

  usb_host_config_t hostConfig = {};
  hostConfig.skip_phy_setup = false;
  hostConfig.intr_flags = ESP_INTR_FLAG_LEVEL1;
  esp_err_t err = usb_host_install(&hostConfig);
  if (err != ESP_OK) {
    Serial.printf("UsbHidOxHost: usb_host_install failed: %d\n", err);
    return;
  }

  usb_host_client_config_t clientConfig = {};
  clientConfig.is_synchronous = false;
  clientConfig.max_num_event_msg = 8;
  clientConfig.async.client_event_callback = &UsbHidOxHost::clientEventCallback;
  clientConfig.async.callback_arg = this;
  err = usb_host_client_register(&clientConfig, &clientHandle_);
  if (err != ESP_OK) {
    Serial.printf("UsbHidOxHost: usb_host_client_register failed: %d\n", err);
    return;
  }

  xTaskCreate(&UsbHidOxHost::usbHostTaskTrampoline, "usbHostLib", 8192, this,
             tskIDLE_PRIORITY + 2, nullptr);
}

void UsbHidOxHost::usbHostTaskTrampoline(void *arg) {
  static_cast<UsbHidOxHost *>(arg)->usbHostTaskLoop();
}

void UsbHidOxHost::usbHostTaskLoop() {
  for (;;) {
    uint32_t eventFlags = 0;
    usb_host_lib_handle_events(pdMS_TO_TICKS(50), &eventFlags);
    if (clientHandle_) {
      usb_host_client_handle_events(clientHandle_, 0);
    }
  }
}

void UsbHidOxHost::clientEventCallback(
    const usb_host_client_event_msg_t *eventMsg, void *arg) {
  auto *self = static_cast<UsbHidOxHost *>(arg);
  switch (eventMsg->event) {
  case USB_HOST_CLIENT_EVENT_NEW_DEV:
    self->handleNewDevice(eventMsg->new_dev.address);
    break;
  case USB_HOST_CLIENT_EVENT_DEV_GONE:
    self->handleDeviceGone(eventMsg->dev_gone.dev_hdl);
    break;
  }
}

bool UsbHidOxHost::discoverHidEndpoints() {
  const usb_config_desc_t *configDesc = nullptr;
  if (usb_host_get_active_config_descriptor(deviceHandle_, &configDesc) !=
          ESP_OK ||
      !configDesc) {
    return false;
  }

  for (int ifaceNum = 0; ifaceNum < configDesc->bNumInterfaces; ifaceNum++) {
    int offset = 0;
    const usb_intf_desc_t *intf = usb_parse_interface_descriptor(
        configDesc, static_cast<uint8_t>(ifaceNum), /*bAlternateSetting=*/0,
        &offset);
    if (!intf) {
      continue;
    }

    uint8_t foundIn = 0;
    uint8_t foundOut = 0;
    for (int epIdx = 0; epIdx < intf->bNumEndpoints; epIdx++) {
      int epOffset = 0;
      const usb_ep_desc_t *ep = usb_parse_endpoint_descriptor_by_index(
          intf, epIdx, configDesc->wTotalLength, &epOffset);
      if (!ep) {
        continue;
      }
      if (USB_EP_DESC_GET_XFERTYPE(ep) != USB_TRANSFER_TYPE_INTR) {
        continue; // Only interrupt endpoints are relevant for HID reports.
      }
      const bool isIn = USB_EP_DESC_GET_EP_DIR(ep) != 0;
      if (isIn && !foundIn) {
        foundIn = ep->bEndpointAddress;
      } else if (!isIn && !foundOut) {
        foundOut = ep->bEndpointAddress;
      }
    }

    if (foundIn) {
      interfaceNumber_ = intf->bInterfaceNumber;
      inEndpointAddress_ = foundIn;
      if (foundOut) {
        outEndpointAddress_ = foundOut;
        haveOutEndpoint_ = true;
        Serial.println(
            "UsbHidOxHost: found interrupt IN and OUT endpoints — using "
            "interrupt transfers for both directions.");
      } else {
        haveOutEndpoint_ = false;
        Serial.println(
            "UsbHidOxHost: found interrupt IN endpoint but no interrupt OUT "
            "endpoint — falling back to control-transfer HID SET_REPORT for "
            "OUT reports (see the plan's spike question).");
      }
      return true;
    }
  }
  return false;
}

void UsbHidOxHost::handleNewDevice(uint8_t address) {
  usb_device_handle_t devHdl = nullptr;
  if (usb_host_device_open(clientHandle_, address, &devHdl) != ESP_OK) {
    return;
  }

  const usb_device_desc_t *deviceDesc = nullptr;
  if (usb_host_get_device_descriptor(devHdl, &deviceDesc) != ESP_OK ||
      !deviceDesc) {
    usb_host_device_close(clientHandle_, devHdl);
    return;
  }

  if (deviceDesc->idVendor != Config::kUsbVendorId ||
      deviceDesc->idProduct != Config::kUsbProductId) {
    // Not the PO-400 — ignore (some other USB device on the same host port,
    // e.g. during the mandatory hardware spike's exploratory testing).
    Serial.printf(
        "UsbHidOxHost: ignoring USB device VID:PID %04X:%04X (not a "
        "PO-400).\n",
        deviceDesc->idVendor, deviceDesc->idProduct);
    usb_host_device_close(clientHandle_, devHdl);
    return;
  }

  Serial.println(
      "UsbHidOxHost: matching VID:PID device attached — enumerating...");
  deviceHandle_ = devHdl;

  if (!discoverHidEndpoints()) {
    Serial.println(
        "UsbHidOxHost: matched VID:PID but found no usable interrupt IN "
        "endpoint on any interface — cannot proceed.");
    closeDevice();
    return;
  }

  if (usb_host_interface_claim(clientHandle_, deviceHandle_, interfaceNumber_,
                               /*bAlternateSetting=*/0) != ESP_OK) {
    Serial.println("UsbHidOxHost: usb_host_interface_claim failed.");
    closeDevice();
    return;
  }

  if (usb_host_transfer_alloc(Config::kHidReportSize, 0, &inTransfer_) !=
      ESP_OK) {
    closeDevice();
    return;
  }
  inTransfer_->device_handle = deviceHandle_;
  inTransfer_->bEndpointAddress = inEndpointAddress_;
  inTransfer_->callback = &UsbHidOxHost::inTransferCallback;
  inTransfer_->context = this;
  inTransfer_->num_bytes = Config::kHidReportSize;
  usb_host_transfer_submit(inTransfer_);

  // Sized to cover both the interrupt-OUT path (report bytes at offset 0)
  // and the control-transfer path (8-byte setup packet + report bytes).
  usb_host_transfer_alloc(kControlTransferBufferSize, 0, &outTransfer_);

  attached_ = true;
  Serial.println("UsbHidOxHost: PO-400 matched, claimed, IN transfer armed.");
  if (attachCallback_) {
    attachCallback_();
  }
}

void UsbHidOxHost::handleDeviceGone(usb_device_handle_t deviceHandle) {
  if (deviceHandle != deviceHandle_) {
    return;
  }
  Serial.println("UsbHidOxHost: PO-400 device-gone event received.");
  attached_ = false;
  if (detachCallback_) {
    detachCallback_();
  }
  closeDevice();
}

void UsbHidOxHost::closeDevice() {
  if (inTransfer_) {
    usb_host_transfer_free(inTransfer_);
    inTransfer_ = nullptr;
  }
  if (outTransfer_) {
    usb_host_transfer_free(outTransfer_);
    outTransfer_ = nullptr;
  }
  if (deviceHandle_) {
    usb_host_interface_release(clientHandle_, deviceHandle_, interfaceNumber_);
    usb_host_device_close(clientHandle_, deviceHandle_);
    deviceHandle_ = nullptr;
  }
  haveOutEndpoint_ = false;
}

void UsbHidOxHost::inTransferCallback(usb_transfer_t *transfer) {
  auto *self = static_cast<UsbHidOxHost *>(transfer->context);
  // actual_num_bytes == 0 shows up as a legitimate COMPLETED transfer with
  // no data — a zero-length-packet completion, seemingly emitted right
  // after the device processes an OUT report and before its real reply is
  // ready. Every real report in this protocol is a fixed 64 bytes (both
  // live-stream and stored-record exchanges), so a 0-byte completion is
  // never meaningful data; queueing it produced an all-zero "report" that
  // every consumer had to misinterpret as a genuine (if garbled) response.
  // Linux's hidraw driver — what pulseoxdl reads through — evidently
  // filters these out transparently; discard them here for the same
  // effect, rather than leaving every caller to notice and skip them.
  if (transfer->status == USB_TRANSFER_STATUS_COMPLETED &&
      transfer->actual_num_bytes > 0 && self->reportQueue_) {
    ReportMessage msg{};
    const size_t n =
        static_cast<size_t>(transfer->actual_num_bytes) < sizeof(msg.data)
            ? static_cast<size_t>(transfer->actual_num_bytes)
            : sizeof(msg.data);
    memcpy(msg.data, transfer->data_buffer, n);
    msg.length = n;
    xQueueSend(self->reportQueue_, &msg, 0);
  }
  // Keep the IN endpoint continuously polled for as long as the device
  // stays attached (mirrors the ~60 reports/sec sustained IN streaming the
  // plan describes).
  if (self->attached_) {
    transfer->num_bytes = Config::kHidReportSize;
    usb_host_transfer_submit(transfer);
  }
}

void UsbHidOxHost::outTransferCallback(usb_transfer_t *transfer) {
  auto *self = static_cast<UsbHidOxHost *>(transfer->context);
  self->lastOutTransferOk_ = transfer->status == USB_TRANSFER_STATUS_COMPLETED;
  xSemaphoreGive(self->outTransferDoneSignal_);
}

void UsbHidOxHost::controlTransferCallback(usb_transfer_t *transfer) {
  auto *self = static_cast<UsbHidOxHost *>(transfer->context);
  self->lastOutTransferOk_ = transfer->status == USB_TRANSFER_STATUS_COMPLETED;
  xSemaphoreGive(self->outTransferDoneSignal_);
}

bool UsbHidOxHost::submitOutInterruptTransfer(const uint8_t *paddedReport) {
  memcpy(outTransfer_->data_buffer, paddedReport, Config::kHidReportSize);
  outTransfer_->device_handle = deviceHandle_;
  outTransfer_->bEndpointAddress = outEndpointAddress_;
  outTransfer_->callback = &UsbHidOxHost::outTransferCallback;
  outTransfer_->context = this;
  outTransfer_->num_bytes = Config::kHidReportSize;
  lastOutTransferOk_ = false;
  if (usb_host_transfer_submit(outTransfer_) != ESP_OK) {
    return false;
  }
  xSemaphoreTake(outTransferDoneSignal_, pdMS_TO_TICKS(2000));
  return lastOutTransferOk_;
}

bool UsbHidOxHost::submitOutControlTransfer(const uint8_t *paddedReport) {
  usb_setup_packet_t *setup =
      reinterpret_cast<usb_setup_packet_t *>(outTransfer_->data_buffer);
  setup->bmRequestType = USB_BM_REQUEST_TYPE_DIR_OUT |
                        USB_BM_REQUEST_TYPE_TYPE_CLASS |
                        USB_BM_REQUEST_TYPE_RECIP_INTERFACE;
  setup->bRequest = kHidRequestSetReport;
  setup->wValue = static_cast<uint16_t>(kHidReportTypeOutput << 8); // report ID 0
  setup->wIndex = interfaceNumber_;
  setup->wLength = Config::kHidReportSize;
  memcpy(outTransfer_->data_buffer + 8, paddedReport, Config::kHidReportSize);

  outTransfer_->device_handle = deviceHandle_;
  outTransfer_->bEndpointAddress = 0; // control transfers target EP0
  outTransfer_->callback = &UsbHidOxHost::controlTransferCallback;
  outTransfer_->context = this;
  outTransfer_->num_bytes = 8 + Config::kHidReportSize;
  lastOutTransferOk_ = false;
  if (usb_host_transfer_submit_control(clientHandle_, outTransfer_) !=
      ESP_OK) {
    return false;
  }
  xSemaphoreTake(outTransferDoneSignal_, pdMS_TO_TICKS(2000));
  return lastOutTransferOk_;
}

bool UsbHidOxHost::readReport(uint8_t *buf, size_t bufSize,
                              uint32_t timeoutMs) {
  if (!reportQueue_) {
    return false;
  }
  ReportMessage msg;
  if (xQueueReceive(reportQueue_, &msg, pdMS_TO_TICKS(timeoutMs)) != pdTRUE) {
    return false;
  }
  const size_t n = msg.length < bufSize ? msg.length : bufSize;
  memcpy(buf, msg.data, n);
  if (n < bufSize) {
    memset(buf + n, 0, bufSize - n);
  }
  return true;
}

bool UsbHidOxHost::writeReport(const uint8_t *data, size_t length) {
  if (!attached_ || !outTransfer_) {
    return false;
  }
  uint8_t padded[Config::kHidReportSize] = {0};
  const size_t n = length < sizeof(padded) ? length : sizeof(padded);
  memcpy(padded, data, n);

  return haveOutEndpoint_ ? submitOutInterruptTransfer(padded)
                          : submitOutControlTransfer(padded);
}
