#pragma once

// Appends a report of fatal native signals (signal, pc/lr, frame chain as library+offset) to the
// file at path, then lets the previous handler run. Call once per process.
void installNativeCrashLog(const char *path);
