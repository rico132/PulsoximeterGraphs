// main.cpp — wires all modules together per the plan's task architecture.
//
// setup() constructs everything and starts two FreeRTOS tasks: the BLE send
// task (started inside BleGattServer::begin(), dedicated to the REQUEST_DATA
// dump so it never blocks the NimBLE write callback) and the USB task below
// — the only task allowed to talk to the PO-400 over USB at all, woken by
// either of two independent signals (see g_usbTaskEvents): a fresh attach,
// which downloads any stored records into the CSV buffer, or the phone
// confirming (CLEAR_BUFFER) it has durably received everything currently in
// that buffer, which deletes the matching records from the device — but
// only once, and only if, test mode is off (Config::kDefaultTestMode's own
// "never destroy real data" default). There is no live-measurement mode,
// since the device's single interrupt endpoint is used either for that
// command/response exchange or for unsolicited live streaming, never both
// at once — see StoredRecordDownloader's initial-handshake comment). loop()
// stays idle except for pumping OtaManager's WiFi-idle timeout and a tiny
// serial debug command for provisioning secrets.
//
// IMPORTANT: hardware-in-the-loop validation still required. This firmware
// has not been run against a physical PO-400 or ESP32-S3-USB-OTG board in
// this environment (no USB device attached in this sandbox) — see the plan's
// mandatory USB spike and UsbHidOxHost.h's header comment.

#include <Arduino.h>
#include <esp_task_wdt.h>
#include <freertos/event_groups.h>

#include "BleGattServer.h"
#include "ClockSync.h"
#include "Config.h"
#include "FileCsvBuffer.h"
#include "ICsvBuffer.h"
#include "OtaManager.h"
#include "OtaRollbackGuard.h"
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
OtaRollbackGuard g_otaRollbackGuard;
BleGattServer *g_bleGattServer = nullptr;

// Two independent reasons usbTask needs to wake up and do USB work — an
// EventGroup rather than a plain binary semaphore because either can fire
// (and need handling) on its own, and both must run on this one task, since
// it's the only one allowed to talk to the PO-400 over USB at all (a
// download and a delete running concurrently on separate tasks would race
// over the same USB interface).
EventGroupHandle_t g_usbTaskEvents = nullptr;
constexpr EventBits_t kUsbAttachBit = BIT0;
constexpr EventBits_t kUsbDeletePendingBit = BIT1;

void usbTask(void * /*param*/) {
  for (;;) {
    const EventBits_t bits = xEventGroupWaitBits(
        g_usbTaskEvents, kUsbAttachBit | kUsbDeletePendingBit,
        /*clearOnExit=*/pdTRUE, /*waitForAllBits=*/pdFALSE, portMAX_DELAY);

    if (bits & kUsbAttachBit) {
      Serial.println("UsbTask: PO-400 attached.");

      // Stored-record download (Auto/Manual) is the only thing this task
      // does per attach — feeds the same ICsvBuffer, per the plan's
      // "Stored-record download" section. Whatever the outcome, this task
      // then just waits for the next signal (see onDetach() below for the
      // actual "unplugged" log line — there's no wait-for-detach loop here
      // since there's nothing left to do while the device stays attached).
      //
      // Subscribed to the task watchdog only for this call's duration — not
      // during the idle wait above, not during the (separate-task) BLE dump
      // afterward, both of which previously caused false-positive reboots
      // when this was subscribed for usbTask's whole lifetime. Real
      // hardware has shown a rare, still-unexplained hang inside
      // UsbHidOxHost::readReport()'s otherwise-3s-bounded wait — not a
      // scheduling/Serial/BLE issue (all ruled out); most likely a fault in
      // the underlying "beta" usb_host.h driver itself, per its own
      // header's warning. Test mode keeps every record on the device, so a
      // reboot here is safe: the next attach just retries the whole
      // download fresh, which empirically does eventually succeed.
      esp_task_wdt_add(nullptr);
      const bool downloadOk = g_storedRecordDownloader->downloadAndMaybeDelete();
      esp_task_wdt_delete(nullptr);
      if (!downloadOk) {
        Serial.println(
            "UsbTask: stored-record download failed or device detached "
            "during it.");
      } else {
        Serial.println(
            "UsbTask: stored-record download complete; nothing more to do "
            "until the device is unplugged.");
      }
    }

    if (bits & kUsbDeletePendingBit) {
      // See StoredRecordDownloader::deleteConfirmedRecords()'s own comment
      // — a no-op unless test mode is off and something's actually pending.
      esp_task_wdt_add(nullptr);
      g_storedRecordDownloader->deleteConfirmedRecords();
      esp_task_wdt_delete(nullptr);
    }
  }
}

// Minimal serial debug commands for provisioning secrets. Per the plan's
// security note (OTA password) and Config::kPrefsKeyBlePasskey's own comment
// (BLE pairing PIN), both are deliberately ONLY settable this way — never
// reachable via any BLE opcode, so a compromised phone/BLE link alone can
// never push firmware or set its own known pairing PIN. Usage: type
// `otapass <password>` then Enter, or a bare `blepin` (no argument — the new
// PIN is always randomly generated, never operator-chosen, same as the very
// first boot's own provisioning) then Enter.
void pollSerialDebugCommands() {
  static String line;
  while (Serial.available() > 0) {
    const char c = static_cast<char>(Serial.read());
    if (c == '\n' || c == '\r') {
      if (line.startsWith("otapass ")) {
        g_otaManager.setOtaPasswordFromSerial(
            std::string(line.substring(8).c_str()));
      } else if (line == "blepin") {
        g_bleGattServer->regeneratePairingPasskey();
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
  // Before anything else that could itself crash or hang setup() (Serial.begin() above never
  // does) — see OtaRollbackGuard's own doc. A firmware stuck failing to boot rolls itself back
  // to the last known-good image right here, rebooting before ever reaching the line below.
  g_otaRollbackGuard.begin();
  Serial.println("PulsoxRelay firmware starting.");
  // Config::kFirmwareVersion is a compile-time constant baked into this exact binary's .rodata
  // (see its own comment) — CI passes -D FIRMWARE_VERSION="<release tag>" per build, so this is
  // already whatever version this running image actually is, no separate bookkeeping needed. A
  // BLE firmware update writes an entirely new binary (with its own build's version baked in)
  // into the inactive OTA partition and only flips the boot partition on a verified-good image
  // (see BleFirmwareUpdater's header comment) — so this line reports the correct version
  // immediately after an OTA-triggered reboot too, the same way it always does on any boot.
  Serial.printf("Firmware version: %s\n", Config::kFirmwareVersion);

  // 30s: generous margin over normal operation (every observed read/packet
  // is sub-second) so this never false-triggers on legitimate work, while
  // still recovering automatically from the rare real-hardware hang seen
  // inside UsbHidOxHost::readReport() (Serial, BLE, and cross-/same-core
  // scheduling all ruled out as the cause — see usbTask()'s own comment on
  // where this gets subscribed/unsubscribed, deliberately only around the
  // download call itself so idle-wait and the BLE dump can't false-trigger
  // it the way an earlier, more broadly-scoped attempt did).
  esp_task_wdt_init(30, /*panic=*/true);

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
                                     *g_storedRecordDownloader, g_otaManager,
                                     g_usbHost);
  g_bleGattServer = &bleGattServer;
  g_bleGattServer->begin();

  g_usbTaskEvents = xEventGroupCreate();
  // Pinned to core 1 (Arduino's own loop()/setup() core), deliberately
  // apart from UsbHidOxHost's "usbHostLib" task (core 0, see its own
  // comment) — that task's tight, higher-priority USB event-poll loop
  // starves this one's xQueueReceive() of scheduler time during a burst
  // (confirmed, not just theoretical — see its comment) whenever the two
  // land on the same core, dropping reports out of the queue in between.
  xTaskCreatePinnedToCore(usbTask, "usbTask", 8192, nullptr,
                         tskIDLE_PRIORITY + 1, nullptr, 1);

  g_usbHost.onAttach(
      []() { xEventGroupSetBits(g_usbTaskEvents, kUsbAttachBit); });
  g_usbHost.onDetach([]() { Serial.println("UsbTask: PO-400 detached."); });
  g_usbHost.begin();

  // See StoredRecordDownloader::onDeleteRequested()'s own comment: wakes
  // usbTask (the only task allowed to talk to the PO-400 over USB) rather
  // than deleteConfirmedRecords() running on the NimBLE host thread that
  // requested it, or only ever running whenever the next real attach
  // happens to occur.
  g_storedRecordDownloader->onDeleteRequested(
      []() { xEventGroupSetBits(g_usbTaskEvents, kUsbDeletePendingBit); });

  Serial.println("PulsoxRelay firmware ready.");
  // Last thing in setup(), now that every subsystem above has initialized without crashing or
  // hanging the watchdog — see OtaRollbackGuard's own doc. A no-op unless this boot followed an
  // OTA update that hasn't confirmed itself healthy yet.
  g_otaRollbackGuard.confirmHealthy();
}

void loop() {
  g_otaManager.loop();
  pollSerialDebugCommands();
  delay(10);
}
