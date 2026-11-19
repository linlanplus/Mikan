#include <jni.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>

// Match kernel socket endpoints, including unconnected UDP sendto() and
// IPv4-mapped IPv6. Ambiguous matches are rejected by the caller.
static bool matches(const sockaddr_storage &addr, const char *ip, int port, bool wildcard) {
    const void *bytes;
    int family = addr.ss_family;
    if (family == AF_INET) {
        auto &v = reinterpret_cast<const sockaddr_in &>(addr);
        if (ntohs(v.sin_port) != port) return false;
        if (wildcard && v.sin_addr.s_addr == INADDR_ANY) return true;
        bytes = &v.sin_addr;
    } else if (family == AF_INET6) {
        auto &v = reinterpret_cast<const sockaddr_in6 &>(addr);
        if (ntohs(v.sin6_port) != port) return false;
        if (wildcard && IN6_IS_ADDR_UNSPECIFIED(&v.sin6_addr)) return true;
        if (IN6_IS_ADDR_V4MAPPED(&v.sin6_addr)) {
            family = AF_INET;
            bytes = v.sin6_addr.s6_addr + 12;
        } else
            bytes = &v.sin6_addr;
    } else
        return false;
    unsigned char expected[16];
    return inet_pton(family, ip, expected) == 1 && memcmp(bytes, expected, family == AF_INET ? 4 : 16) == 0;
}
jlong socketOwner(JNIEnv *env, jclass, jint type, jstring src, jint sp, jstring dst, jint dp) {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return -1;
    const char *s = env->GetStringUTFChars(src, nullptr), *d = env->GetStringUTFChars(dst, nullptr);
    jlong result = 0;
    while (auto *entry = readdir(dir)) {
        char *end;
        long index = strtol(entry->d_name, &end, 10);
        if (*end || index < 0) continue;
        int t;
        socklen_t len = sizeof(t);
        if (getsockopt(index, SOL_SOCKET, SO_TYPE, &t, &len) || t != type) continue;
        int fd = fcntl(index, F_DUPFD_CLOEXEC, 0);
        if (fd < 0) continue;
        sockaddr_storage local{}, peer{};
        len = sizeof(local);
        bool match =
            !getsockname(fd, reinterpret_cast<sockaddr *>(&local), &len) && matches(local, s, sp, true);
        len = sizeof(peer);
        if (match && !getpeername(fd, reinterpret_cast<sockaddr *>(&peer), &len))
            match = matches(peer, d, dp, false);
        if (match && type == SOCK_STREAM) {
            int listening = 0;
            len = sizeof(listening);
            if (getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, &listening, &len) || listening) match = false;
        }
        struct stat st{};
        if (match && !fstat(fd, &st)) {
            jlong id = static_cast<jlong>(st.st_ino);
            result = result && result != id ? -1 : id;
        }
        close(fd);
        if (result < 0) break;
    }
    closedir(dir);
    env->ReleaseStringUTFChars(src, s);
    env->ReleaseStringUTFChars(dst, d);
    return result;
}
