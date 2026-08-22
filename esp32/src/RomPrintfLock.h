// RomPrintfLock.h — cross-core mutual exclusion for esp_rom_printf().
//
// esp_rom_printf() busy-waits directly on the UART hardware, bypassing the
// driver entirely — that's exactly what makes it safe to call from a hot
// loop that can't risk Serial's interrupt-fed TX ring buffer stalling (see
// StoredRecordDownloader.cpp's extensive comments on that whole saga). But
// it does nothing to serialize access to that shared hardware register
// between callers. That was harmless for as long as every esp_rom_printf
// call site in this firmware ran on the same core: FreeRTOS's scheduler
// only ever runs one task at a time on a given core, so calls could
// interleave at a coarse (whole-call) granularity at worst, never mid-byte.
//
// That assumption broke the moment UsbHidOxHost's "usbHostLib" task moved
// to core 0 (see its own comment) while StoredRecordDownloader's usbTask
// stayed on core 1 — the two cores can now call esp_rom_printf at the
// literal same instant. Real hardware confirmed it: RR# heartbeats (core 1)
// and dropped-report notices (core 0) came back visibly interleaved
// byte-for-byte into a garbled, unreadable mess.
//
// Every esp_rom_printf call site in this firmware should go through
// LOCKED_ROM_PRINTF instead, which wraps the call in this spinlock.
#pragma once

#include <esp_rom_sys.h>
#include <freertos/FreeRTOS.h>

extern portMUX_TYPE g_romPrintfLock;

class RomPrintfGuard {
public:
  RomPrintfGuard() { portENTER_CRITICAL(&g_romPrintfLock); }
  ~RomPrintfGuard() { portEXIT_CRITICAL(&g_romPrintfLock); }

  RomPrintfGuard(const RomPrintfGuard &) = delete;
  RomPrintfGuard &operator=(const RomPrintfGuard &) = delete;
};

#define LOCKED_ROM_PRINTF(...)                                               \
  do {                                                                       \
    RomPrintfGuard romPrintfGuard_;                                          \
    esp_rom_printf(__VA_ARGS__);                                             \
  } while (0)
