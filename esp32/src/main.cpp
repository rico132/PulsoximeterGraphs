// main.cpp — wires all modules together per the plan's task architecture.
//
// setup() constructs everything and starts two FreeRTOS tasks: the BLE send
// task (started inside BleGattServer::begin(), dedicated to the REQUEST_DATA
// dump so it never blocks the NimBLE write callback) and the USB task below
// (owns the PO-400 lifecycle: on every attach, download+maybe-delete any
// stored records — that's the only thing the PO-400's USB link is used for;
// there is no live-measurement mode, since the device's single interrupt
// endpoint is used either for that command/response exchange or for
// unsolicited live streaming, never both at once — see
// StoredRecordDownloader's initial-handshake comment). loop() stays idle
// except for pumping OtaManager's WiFi-idle timeout and a tiny serial debug
// command for provisioning the OTA password.
//
// IMPORTANT: hardware-in-the-loop validation still required. This firmware
// has not been run against a physical PO-400 or ESP32-S3-USB-OTG board in
// this environment (no USB device attached in this sandbox) — see the plan's
// mandatory USB spike and UsbHidOxHost.h's header comment.

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "BleGattServer.h"
#include "ClockSync.h"
#include "Config.h"
#include "FileCsvBuffer.h"
#include "ICsvBuffer.h"
#include "OtaManager.h"
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

void usbTask(void * /*param*/) {
  // Self-subscribes to the task watchdog initialized in setup(). Every
  // UsbHidOxHost::readReport() call (the one point every download/decode
  // exchange passes through) resets it, so a genuine, unbounded hang
  // anywhere else in that call chain — observed against real hardware as
  // a download that silently stops making any progress at all, with none
  // of the code's own bounded-timeout paths ever firing — trips a panic
  // with a full backtrace at the exact point it's stuck, instead of
  // indefinite silence with no way to tell where.
  esp_task_wdt_add(nullptr);
  for (;;) {
    if (xSemaphoreTake(g_usbAttachSignal, portMAX_DELAY) == pdTRUE) {
      Serial.println("UsbTask: PO-400 attached.");

      // Stored-record download (Auto/Manual) is the only thing this task
      // does per attach — feeds the same ICsvBuffer, per the plan's
      // "Stored-record download" section. Whatever the outcome, this task
      // then just waits for the next attach signal (see onDetach() below for
      // the actual "unplugged" log line — there's no wait-for-detach loop
      // here since there's nothing left to do while the device stays
      // attached).
      if (!g_storedRecordDownloader->downloadAndMaybeDelete()) {
        Serial.println(
            "UsbTask: stored-record download failed or device detached "
            "during it.");
      } else {
        Serial.println(
            "UsbTask: stored-record download complete; nothing more to do "
            "until the device is unplugged.");
      }
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

  // 20s: comfortably longer than any single bounded wait in the download
  // path (readReport()'s own 3s timeout, the loudest single wait) so
  // normal operation — however slow a real transfer legitimately gets —
  // never false-triggers this, while still firing far sooner than the
  // several minutes a real hang has otherwise gone unnoticed for. See
  // usbTask()'s and UsbHidOxHost::readReport()'s comments for how this
  // gets subscribed to and reset.
  esp_task_wdt_init(20, /*panic=*/true);

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

  static StoredRecordDownloader storedRecordDownloader(g_usbHost, *g_csvBuffer,
                                                       g_clockSync);
  g_storedRecordDownloader = &storedRecordDownloader;
  g_storedRecordDownloader->begin();

  g_otaManager.begin();

  static BleGattServer bleGattServer(*g_csvBuffer, g_clockSync,
                                     *g_storedRecordDownloader, g_otaManager);
  g_bleGattServer = &bleGattServer;
  g_bleGattServer->begin();

  g_usbAttachSignal = xSemaphoreCreateBinary();
  // Pinned to core 1 (Arduino's own loop()/setup() core), deliberately
  // apart from UsbHidOxHost's "usbHostLib" task (core 0, see its own
  // comment) — that task's tight, higher-priority USB event-poll loop
  // could otherwise starve this one on a long decode if the scheduler
  // happened to land both on the same core.
  xTaskCreatePinnedToCore(usbTask, "usbTask", 8192, nullptr,
                         tskIDLE_PRIORITY + 1, nullptr, 1);

  g_usbHost.onAttach([]() { xSemaphoreGive(g_usbAttachSignal); });
  g_usbHost.onDetach([]() { Serial.println("UsbTask: PO-400 detached."); });
  g_usbHost.begin();

  Serial.println("PulsoxRelay firmware ready.");
}

void loop() {
  g_otaManager.loop();
  pollSerialDebugCommands();
  delay(10);
}
