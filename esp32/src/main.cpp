// main.cpp — wires all modules together per the plan's task architecture.
//
// setup() constructs everything and starts two FreeRTOS tasks: the BLE send
// task (started inside BleGattServer::begin(), dedicated to the REQUEST_DATA
// dump so it never blocks the NimBLE write callback) and the USB task below
// (owns the PO-400 lifecycle: on attach, download+maybe-delete any stored
// records, then run the live-stream loop for as long as the device stays
// attached). loop() stays idle except for pumping OtaManager's WiFi-idle
// timeout and a tiny serial debug command for provisioning the OTA password.
//
// IMPORTANT: hardware-in-the-loop validation still required. This firmware
// has not been run against a physical PO-400 or ESP32-S3-USB-OTG board in
// this environment (no USB device attached in this sandbox) — see the plan's
// mandatory USB spike and UsbHidOxHost.h's header comment.

#include <Arduino.h>

#include "BleGattServer.h"
#include "ClockSync.h"
#include "Config.h"
#include "FileCsvBuffer.h"
#include "ICsvBuffer.h"
#include "OtaManager.h"
#include "OxProtocolParser.h"
#include "RamCsvBuffer.h"
#include "StoredRecordDownloader.h"
#include "UsbHidOxHost.h"

namespace {

RamCsvBuffer g_ramCsvBuffer;
FileCsvBuffer g_fileCsvBuffer;
ICsvBuffer *g_csvBuffer = nullptr;

ClockSync g_clockSync(millis);
UsbHidOxHost g_usbHost;
// Constructed in setup(), once g_csvBuffer is known, to avoid binding a
// reference to it before it points anywhere valid.
StoredRecordDownloader *g_storedRecordDownloader = nullptr;
OtaManager g_otaManager;
BleGattServer *g_bleGattServer = nullptr;

SemaphoreHandle_t g_usbAttachSignal = nullptr;

// Bridges OxProtocolParser's decoded live measurements into the CSV buffer,
// gated on ClockSync per the plan's "Timestamp gating" note: don't append
// any row until the phone has sent SET_TIME at least once, since a
// placeholder epoch would silently produce misleading data.
class LiveMeasurementListener : public OxProtocolParserListener {
public:
  void onMeasurement(const OxMeasurement &measurement) override {
    if (!g_clockSync.hasBeenSet()) {
      return;
    }
    g_csvBuffer->appendRow(g_clockSync.now(), measurement.spo2,
                          measurement.pulseRate);
  }
};

LiveMeasurementListener g_liveListener;
OxProtocolParser g_liveParser(g_liveListener);

void runLiveStreamLoop() {
  g_usbHost.writeReport(Config::kCmdStartAmplitudes,
                       sizeof(Config::kCmdStartAmplitudes));
  g_usbHost.writeReport(Config::kCmdStartMeasurements,
                       sizeof(Config::kCmdStartMeasurements));

  uint8_t report[Config::kHidReportSize];
  // Confirm the device's 0xEB-prefixed ack per the plan; if it doesn't show
  // up promptly we still fall through to the read loop below rather than
  // give up entirely, since a missed ack read is not itself fatal.
  if (g_usbHost.readReport(report, sizeof(report), 1000)) {
    if (report[0] != 0xEB) {
      Serial.println(
          "UsbTask: warning — expected 0xEB-prefixed ack after live-stream "
          "start commands, got something else.");
    }
  }

  unsigned long lastKeepaliveMs = millis();
  while (g_usbHost.isAttached()) {
    if (g_usbHost.readReport(report, sizeof(report), 200)) {
      g_liveParser.parseReport(report, sizeof(report));
    }
    if (millis() - lastKeepaliveMs >= Config::kKeepaliveIntervalMs) {
      g_usbHost.writeReport(Config::kCmdKeepalive,
                           sizeof(Config::kCmdKeepalive));
      lastKeepaliveMs = millis();
    }
  }

  g_usbHost.writeReport(Config::kCmdStopLive, sizeof(Config::kCmdStopLive));
}

void usbTask(void * /*param*/) {
  for (;;) {
    if (xSemaphoreTake(g_usbAttachSignal, portMAX_DELAY) == pdTRUE) {
      Serial.println("UsbTask: PO-400 attached.");

      // Stored-record download (Auto/Manual), before live streaming — feeds
      // the same ICsvBuffer, per the plan's "Stored-record download" section.
      if (!g_storedRecordDownloader->downloadAndMaybeDelete()) {
        Serial.println(
            "UsbTask: stored-record download failed or device detached "
            "during it; skipping live stream this session.");
        continue;
      }

      if (g_usbHost.isAttached()) {
        runLiveStreamLoop();
      }
      Serial.println("UsbTask: PO-400 detached.");
    }
  }
}

// Minimal serial debug command for provisioning the OTA password. Per the
// plan's security note, this is deliberately the ONLY way to set it — never
// reachable via any BLE opcode, so a compromised phone/BLE link alone can
// never push firmware. Usage: type `otapass <password>` then Enter.
void pollSerialDebugCommands() {
  static String line;
  while (Serial.available() > 0) {
    const char c = static_cast<char>(Serial.read());
    if (c == '\n' || c == '\r') {
      if (line.startsWith("otapass ")) {
        g_otaManager.setOtaPasswordFromSerial(
            std::string(line.substring(8).c_str()));
      }
      line = "";
    } else {
      line += c;
    }
  }
}

} // namespace

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("PulsoxRelay firmware starting.");

  if (g_ramCsvBuffer.begin()) {
    g_csvBuffer = &g_ramCsvBuffer;
    Serial.println("Using PSRAM-backed CSV buffer.");
  } else if (g_fileCsvBuffer.begin()) {
    g_csvBuffer = &g_fileCsvBuffer;
    Serial.println(
        "No PSRAM available — falling back to LittleFS-backed CSV buffer.");
  } else {
    Serial.println(
        "FATAL: neither RAM nor LittleFS CSV buffer could be initialized.");
  }

  static StoredRecordDownloader storedRecordDownloader(g_usbHost,
                                                       *g_csvBuffer);
  g_storedRecordDownloader = &storedRecordDownloader;
  g_storedRecordDownloader->begin();

  g_otaManager.begin();

  static BleGattServer bleGattServer(*g_csvBuffer, g_clockSync,
                                     *g_storedRecordDownloader, g_otaManager);
  g_bleGattServer = &bleGattServer;
  g_bleGattServer->begin();

  g_usbAttachSignal = xSemaphoreCreateBinary();
  xTaskCreate(usbTask, "usbTask", 8192, nullptr, tskIDLE_PRIORITY + 1,
             nullptr);

  g_usbHost.onAttach([]() { xSemaphoreGive(g_usbAttachSignal); });
  g_usbHost.begin();

  Serial.println("PulsoxRelay firmware ready.");
}

void loop() {
  g_otaManager.loop();
  pollSerialDebugCommands();
  delay(10);
}
