#include <sys/system_properties.h>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
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
    if (lookup_override(name, spoofed)) {
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
    if (lookup_override(name, spoofed)) {
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

static void hook_symbol(void *handle, const char *symbol, void *replace, void **orig) {
    void *target = xdl_dsym(handle, symbol, nullptr);
    if (target == nullptr) {
        LOGD("Spoof: %s not found", symbol);
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
