#include <sys/system_properties.h>
#include <cstring>
#include <cstdio>
#include <cinttypes>
#include <mutex>
#include <string>
#include <unordered_map>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include <unwind.h>
#include "Dobby/dobby.h"
#include "VirtualSpoof.h"


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

typedef void (*prop_read_cb)(void *cookie, const char *name, const char *value, uint32_t serial);

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static void (*orig_system_property_read_callback)(const prop_info *pi, prop_read_cb callback, void *cookie) = nullptr;

// Property name -> spoofed value. Empty in the host process, filled per guest process.
static std::mutex g_spoof_mutex;
static std::unordered_map<std::string, std::string> g_overrides;

static bool lookup_override(const char *name, std::string &out) {
    if (name == nullptr) return false;
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    if (g_overrides.empty()) return false;
    auto it = g_overrides.find(name);
    if (it == g_overrides.end()) return false;
    out = it->second;
    return true;
}

// Load base of this library, so its own frames are skipped when looking for the caller. Found on
// first use: a constructor could run before this file's C++ globals are initialized.
static const void *g_self_base = nullptr;

static bool is_self(const Dl_info &info) {
    if (g_self_base == nullptr) {
        Dl_info self{};
        if (dladdr(reinterpret_cast<void *>(is_self), &self) != 0) {
            g_self_base = self.dli_fbase;
        }
    }
    return info.dli_fbase == g_self_base;
}

static bool starts_with(const char *s, const char *prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static bool ends_with(const char *s, const char *suffix) {
    size_t n = strlen(s), m = strlen(suffix);
    return n >= m && strcmp(s + n - m, suffix) == 0;
}

// Properties the platform's own native code uses to pick drivers and HAL libraries: the Vulkan
// and EGL loaders, gralloc and codec clients look for files named after them. A chip that differs
// from the real one sends them to libraries the phone does not have, or down another vendor's code
// path, and the guest dies with no Java trace (MediaTek presets on a Snapdragon phone).
static bool is_chip_prop(const char *name) {
    if (strcmp(name, "ro.hardware") == 0 || strcmp(name, "ro.hardware.chipname") == 0
            || strcmp(name, "ro.board.platform") == 0 || strcmp(name, "ro.soc.manufacturer") == 0
            || strcmp(name, "ro.soc.model") == 0) {
        return true;
    }
    return starts_with(name, "ro.product.") && ends_with(name, "board");
}

struct Frames {
    uintptr_t pc[24];
    int count;
};

static _Unwind_Reason_Code collect_frame(struct _Unwind_Context *context, void *arg) {
    auto *frames = static_cast<Frames *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        frames->pc[frames->count++] = pc;
    }
    return frames->count < 24 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

// Libraries that only pass a property read along. The caller is the first frame past them.
static bool is_property_plumbing(const Dl_info &info) {
    const char *path = info.dli_fname;
    return ends_with(path, "/libc.so") || ends_with(path, "/libbase.so")
           || ends_with(path, "/libcutils.so") || is_self(info);
}

// Whether the code reading a chip property should see the spoofed value. Java reads arrive through
// libandroid_runtime (SystemProperties) and the guest's own libraries live under /data; both see the
// clone's chip. Native code of the platform and the vendor sees the real one.
static bool caller_sees_spoof() {
    Frames frames{};
    _Unwind_Backtrace(collect_frame, &frames);
    for (int i = 0; i < frames.count; i++) {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void *>(frames.pc[i]), &info) == 0 || info.dli_fname == nullptr) {
            continue;
        }
        const char *path = info.dli_fname;
        if (is_property_plumbing(info)) {
            continue;
        }
        if (ends_with(path, "/libandroid_runtime.so")) {
            return true;
        }
        return !(starts_with(path, "/system/") || starts_with(path, "/vendor/")
                 || starts_with(path, "/apex/") || starts_with(path, "/odm/")
                 || starts_with(path, "/system_ext/") || starts_with(path, "/product/"));
    }
    return true;
}

static bool lookup_override_for_caller(const char *name, std::string &out) {
    if (!lookup_override(name, out)) return false;
    return !is_chip_prop(name) || caller_sees_spoof();
}

void setPropOverride(const char *name, const char *value) {
    if (name == nullptr || value == nullptr) return;
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    g_overrides[name] = value;
}

void clearPropOverrides() {
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    g_overrides.clear();
}

int my_system_property_get(const char *name, char *value) {
    std::string spoofed;
    if (lookup_override_for_caller(name, spoofed)) {
        // Callers provide a PROP_VALUE_MAX buffer.
        size_t len = spoofed.size() < PROP_VALUE_MAX - 1 ? spoofed.size() : PROP_VALUE_MAX - 1;
        memcpy(value, spoofed.c_str(), len);
        value[len] = '\0';
        return static_cast<int>(len);
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

struct ReadCallbackContext {
    prop_read_cb callback;
    void *cookie;
};

static void spoof_read_callback(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *ctx = static_cast<ReadCallbackContext *>(cookie);
    std::string spoofed;
    if (lookup_override_for_caller(name, spoofed)) {
        ctx->callback(ctx->cookie, name, spoofed.c_str(), serial);
    } else {
        ctx->callback(ctx->cookie, name, value, serial);
    }
}

// Java SystemProperties and android::base::GetProperty read through this, not __system_property_get.
void my_system_property_read_callback(const prop_info *pi, prop_read_cb callback, void *cookie) {
    if (callback == nullptr) {
        orig_system_property_read_callback(pi, callback, cookie);
        return;
    }
    ReadCallbackContext ctx{callback, cookie};
    orig_system_property_read_callback(pi, spoof_read_callback, &ctx);
}

// A native bridge can map guest libc code as read-only data and execute translated copies.
static bool is_executable_address(void *address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;
    bool executable = false;
    char line[512], perm[5];
    uintptr_t start, end, target = reinterpret_cast<uintptr_t>(address);
    while (fgets(line, sizeof(line), maps)) {
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s", &start, &end, perm) == 3
                && start <= target && target < end) {
            executable = perm[2] == 'x';
            break;
        }
    }
    fclose(maps);
    return executable;
}

static void hook_symbol(void *handle, const char *symbol, void *replace, void **orig) {
    void *target = xdl_dsym(handle, symbol, nullptr);
    if (target == nullptr) {
        LOGD("Spoof: %s not found", symbol);
        return;
    }
    if (!is_executable_address(target)) {
        LOGD("Spoof: %s has no executable mapping", symbol);
        return;
    }
    if (DobbyHook(target, replace, orig) == 0) {
        LOGD("Spoof: hooked %s", symbol);
    } else {
        LOGD("Spoof: hook %s failed", symbol);
    }
}

void install_property_get_hook() {
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == nullptr) return;
    hook_symbol(handle, "__system_property_get",
                (void *) my_system_property_get, (void **) &orig_system_property_get);
    hook_symbol(handle, "__system_property_read_callback",
                (void *) my_system_property_read_callback, (void **) &orig_system_property_read_callback);
    xdl_close(handle);
}


__attribute__((constructor)) void init_virtual_spoof()
{
    install_property_get_hook();
    LOGD("VirtualSpoof: property hooks loaded");
}
