#include <jni.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <cstddef>
#include <string>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <array>
#include <vector>
#include "../xdl.h"
#include "../Dobby/dobby.h"

using IP = std::array<unsigned char, 16>;
using AddrInfo = decltype(&getaddrinfo);
static AddrInfo original;
static bool selected;
static std::string controlName;
jboolean cloneDnsActive(JNIEnv *, jclass) {
    if (selected) return true;
    if (controlName.empty()) return false;
    sockaddr_un a{};
    a.sun_family = AF_UNIX;
    if (controlName.size() >= sizeof(a.sun_path) - 1) return false;
    memcpy(a.sun_path + 1, controlName.data(), controlName.size());
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return false;
    int rc = connect(fd, reinterpret_cast<sockaddr *>(&a),
                     offsetof(sockaddr_un, sun_path) + 1 + controlName.size());
    close(fd);
    return rc == 0;
}
static bool transfer(int fd, void *buffer, size_t size, bool sending) {
    auto p = static_cast<unsigned char *>(buffer);
    while (size) {
        ssize_t n = sending ? send(fd, p, size, MSG_NOSIGNAL) : recv(fd, p, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= n;
    }
    return true;
}
// The TCP socket belongs to this clone, so the gateway can select its node
// before querying DNS. No libc resolver fallback is permitted on an error.
static int query(const char *host, int family, std::vector<IP> &ips) {
    size_t len = strlen(host);
    if (!len || len > 253) return EAI_NONAME;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return EAI_AGAIN;
    timeval tv{10, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    sockaddr_in target{};
    target.sin_family = AF_INET;
    target.sin_port = htons(5354);
    inet_pton(AF_INET, "10.112.0.2", &target.sin_addr);
    unsigned char msg[255] = {static_cast<unsigned char>(family == AF_INET ? 4 : 6),
                              static_cast<unsigned char>(len)};
    memcpy(msg + 2, host, len);
    unsigned char count = 0;
    bool ok = connect(fd, reinterpret_cast<sockaddr *>(&target), sizeof(target)) == 0 &&
              transfer(fd, msg, len + 2, true) && transfer(fd, &count, 1, false) && count <= 64;
    std::vector<IP> result(count);
    for (auto &ip : result)
        if (ok) ok = transfer(fd, ip.data(), family == AF_INET ? 4 : 16, false);
    close(fd);
    if (!ok) return EAI_AGAIN;
    ips = std::move(result);
    return count ? 0 : EAI_NONAME;
}
static bool isLocal(const char *host) {
    return !host || !strcasecmp(host, "localhost") || !strcasecmp(host, "localhost.");
}
static int routedAddrInfo(const char *name, const char *service, const addrinfo *hints, addrinfo **out) {
    addrinfo local{};
    if (hints) local = *hints;
    local.ai_flags |= AI_NUMERICHOST;
    // Preserve numeric literals, scoped IPv6 and localhost without a network query.
    if (isLocal(name) || !cloneDnsActive(nullptr, nullptr)) return original(name, service, hints, out);
    int err = original(name, service, &local, out);
    if (err == 0 || (hints && (hints->ai_flags & AI_NUMERICHOST))) return err;
    if (local.ai_family != AF_UNSPEC && local.ai_family != AF_INET && local.ai_family != AF_INET6)
        return EAI_FAMILY;
    *out = nullptr;
    addrinfo **tail = out;
    int failure = EAI_NONAME;
    for (int family : {AF_INET, AF_INET6}) {
        if (local.ai_family != AF_UNSPEC && local.ai_family != family) continue;
        std::vector<IP> ips;
        int rc = query(name, family, ips);
        if (rc == EAI_AGAIN) failure = rc;
        for (const auto &ip : ips) {
            char addr[INET6_ADDRSTRLEN];
            inet_ntop(family, ip.data(), addr, sizeof(addr));
            addrinfo *list = nullptr;
            if (original(addr, service, &local, &list) != 0 || !list) continue;
            *tail = list;
            while (*tail) tail = &(*tail)->ai_next;
        }
    }
    return *out ? 0 : failure;
}
void installCloneDns(JNIEnv *env, jclass, jboolean routed, jstring socketName) {
    selected = routed;
    const char *s = env->GetStringUTFChars(socketName, nullptr);
    controlName = s;
    env->ReleaseStringUTFChars(socketName, s);
    static std::once_flag once;
    std::call_once(once, [] {
        void *lib = xdl_open("libc.so", XDL_DEFAULT);
        if (!lib) return;
        void *entry = xdl_dsym(lib, "getaddrinfo", nullptr);
        if (entry)
            DobbyHook(entry, reinterpret_cast<void *>(routedAddrInfo), reinterpret_cast<void **>(&original));
        xdl_close(lib);
    });
}
jobjectArray cloneDns(JNIEnv *env, jclass, jstring host, jint family) {
    const char *s = env->GetStringUTFChars(host, nullptr);
    std::vector<IP> ips;
    int result = query(s, family, ips);
    env->ReleaseStringUTFChars(host, s);
    if (result == EAI_AGAIN) return nullptr;
    jclass bytes = env->FindClass("[B");
    auto out = env->NewObjectArray(ips.size(), bytes, nullptr);
    for (size_t i = 0; i < ips.size(); i++) {
        int n = family == AF_INET ? 4 : 16;
        jbyteArray raw = env->NewByteArray(n);
        env->SetByteArrayRegion(raw, 0, n, reinterpret_cast<const jbyte *>(ips[i].data()));
        env->SetObjectArrayElement(out, i, raw);
        env->DeleteLocalRef(raw);
    }
    return out;
}
