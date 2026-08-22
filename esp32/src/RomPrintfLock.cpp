#include "RomPrintfLock.h"

portMUX_TYPE g_romPrintfLock = portMUX_INITIALIZER_UNLOCKED;
