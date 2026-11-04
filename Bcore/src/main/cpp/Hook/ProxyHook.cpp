#include "ProxyHook.h"
#include "Log.h"
#include "../xdl.h"
#include "../Dobby/dobby.h"

#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <mutex>
#include <string>
#include <vector>

#define PROXY_TAG "ProxyHook"
#define PLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, PROXY_TAG, __VA_ARGS__)
#define PLOGE(...) __android_log_print(ANDROID_LOG_ERROR, PROXY_TAG, __VA_ARGS__)

typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t);

static connect_fn orig_connect = nullptr;
static bool hook_installed = false;

struct ProxyConfig {
    bool enabled = false;
    bool blocked = false;
    std::string host;
    int port = 0;
    std::string user;
    std::string pass;
    // Host resolved once at set time, so the hook itself never calls getaddrinfo.
    std::vector<struct sockaddr_storage> addrs;
};

static std::mutex g_mutex;
static ProxyConfig g_proxy;

// Set while this thread is opening the tunnel's own connection to the VMess server. Without it the
// hook would route that connection back into the local proxy, which would open another tunnel, and
// so on. It is per-thread because the relay runs on its own threads.
static __thread int g_bypass_depth = 0;

static const int HANDSHAKE_TIMEOUT_SEC = 15;

static bool is_loopback(const struct sockaddr *addr) {
    if (addr->sa_family == AF_INET) {
        const auto *in = reinterpret_cast<const struct sockaddr_in *>(addr);
        uint32_t host = ntohl(in->sin_addr.s_addr);
        return (host >> 24) == 127;
    }
    if (addr->sa_family == AF_INET6) {
        const auto *in6 = reinterpret_cast<const struct sockaddr_in6 *>(addr);
        return IN6_IS_ADDR_LOOPBACK(&in6->sin6_addr);
    }
    return false;
}

static bool is_socket_type_stream(int fd) {
    int type = 0;
    socklen_t len = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) return false;
    return type == SOCK_STREAM;
}

// Returns true when addr is the proxy endpoint itself, so we do not proxy the proxy.
static bool is_proxy_endpoint(const struct sockaddr *addr) {
    for (const auto &stored : g_proxy.addrs) {
        if (stored.ss_family != addr->sa_family) continue;
        if (addr->sa_family == AF_INET) {
            const auto *a = reinterpret_cast<const struct sockaddr_in *>(addr);
            const auto *b = reinterpret_cast<const struct sockaddr_in *>(&stored);
            if (a->sin_port == b->sin_port &&
                a->sin_addr.s_addr == b->sin_addr.s_addr) {
                return true;
            }
        } else if (addr->sa_family == AF_INET6) {
            const auto *a = reinterpret_cast<const struct sockaddr_in6 *>(addr);
            const auto *b = reinterpret_cast<const struct sockaddr_in6 *>(&stored);
            if (a->sin6_port == b->sin6_port &&
                memcmp(&a->sin6_addr, &b->sin6_addr, sizeof(struct in6_addr)) == 0) {
                return true;
            }
        }
    }
    return false;
}

static bool send_all(int fd, const void *buf, size_t len) {
    const char *p = static_cast<const char *>(buf);
    while (len > 0) {
        ssize_t n = send(fd, p, len, 0);
        if (n > 0) {
            p += n;
            len -= n;
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static bool recv_all(int fd, void *buf, size_t len) {
    char *p = static_cast<char *>(buf);
    while (len > 0) {
        ssize_t n = recv(fd, p, len, 0);
        if (n > 0) {
            p += n;
            len -= n;
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

// SOCKS5 greeting, optional RFC1929 auth, then CONNECT to the original target.
// The target arrives as a numeric address, since the app has already resolved it.
static bool socks5_handshake(int fd, const struct sockaddr *target, const ProxyConfig &config) {
    unsigned char methods[3];
    int method_count = 0;
    methods[method_count++] = 0x00; // no auth
    bool has_auth = !config.user.empty();
    if (has_auth) methods[method_count++] = 0x02; // username/password

    unsigned char greeting[2 + 2] = {0x05, (unsigned char) method_count};
    memcpy(greeting + 2, methods, method_count);
    if (!send_all(fd, greeting, 2 + method_count)) {
        PLOGE("greeting send failed: %s", strerror(errno));
        return false;
    }

    unsigned char reply[2];
    if (!recv_all(fd, reply, 2)) {
        PLOGE("method reply failed: %s", strerror(errno));
        return false;
    }
    if (reply[0] != 0x05) {
        PLOGE("not a SOCKS5 server (version %d)", reply[0]);
        return false;
    }
    if (reply[1] == 0xFF) {
        PLOGE("proxy rejected all auth methods");
        return false;
    }
    if (reply[1] == 0x02) {
        if (!has_auth) {
            PLOGE("proxy requires auth but no credentials configured");
            return false;
        }
        if (config.user.size() > 255 || config.pass.size() > 255) {
            PLOGE("credentials too long");
            return false;
        }
        unsigned char auth[3 + 255 + 255];
        int off = 0;
        auth[off++] = 0x01;
        auth[off++] = (unsigned char) config.user.size();
        memcpy(auth + off, config.user.data(), config.user.size());
        off += config.user.size();
        auth[off++] = (unsigned char) config.pass.size();
        memcpy(auth + off, config.pass.data(), config.pass.size());
        off += config.pass.size();
        if (!send_all(fd, auth, off)) {
            PLOGE("auth send failed: %s", strerror(errno));
            return false;
        }
        unsigned char auth_reply[2];
        if (!recv_all(fd, auth_reply, 2)) {
            PLOGE("auth reply failed: %s", strerror(errno));
            return false;
        }
        if (auth_reply[1] != 0x00) {
            PLOGE("username/password rejected (status %d)", auth_reply[1]);
            return false;
        }
    }

    unsigned char request[4 + 16 + 2];
    int len = 0;
    request[len++] = 0x05;
    request[len++] = 0x01; // CONNECT
    request[len++] = 0x00;
    if (target->sa_family == AF_INET) {
        const auto *in = reinterpret_cast<const struct sockaddr_in *>(target);
        request[len++] = 0x01;
        memcpy(request + len, &in->sin_addr, 4);
        len += 4;
        memcpy(request + len, &in->sin_port, 2);
        len += 2;
    } else if (target->sa_family == AF_INET6) {
        const auto *in6 = reinterpret_cast<const struct sockaddr_in6 *>(target);
        request[len++] = 0x04;
        memcpy(request + len, &in6->sin6_addr, 16);
        len += 16;
        memcpy(request + len, &in6->sin6_port, 2);
        len += 2;
    } else {
        PLOGE("unsupported address family %d", target->sa_family);
        return false;
    }
    if (!send_all(fd, request, len)) {
        PLOGE("connect request send failed: %s", strerror(errno));
        return false;
    }

    unsigned char head[4];
    if (!recv_all(fd, head, 4)) {
        PLOGE("connect reply failed: %s", strerror(errno));
        return false;
    }
    if (head[0] != 0x05) {
        PLOGE("unexpected reply version %d", head[0]);
        return false;
    }
    if (head[1] != 0x00) {
        PLOGE("proxy refused CONNECT (status %d)", head[1]);
        return false;
    }
    int skip = 0;
    if (head[3] == 0x01) skip = 4 + 2;
    else if (head[3] == 0x04) skip = 16 + 2;
    else if (head[3] == 0x03) {
        unsigned char domain_len = 0;
        if (!recv_all(fd, &domain_len, 1)) return false;
        skip = domain_len + 2;
    } else {
        PLOGE("unexpected reply address type %d", head[3]);
        return false;
    }
    unsigned char drain[257];
    if (skip > 0 && !recv_all(fd, drain, skip)) {
        PLOGE("connect reply drain failed: %s", strerror(errno));
        return false;
    }
    return true;
}

// 1 lets the caller use its original implementation, 0 is a connected descriptor, -1 sets errno.
static int try_proxy_connect(int fd, const struct sockaddr *addr) {
    if (!addr || (addr->sa_family != AF_INET && addr->sa_family != AF_INET6) || g_bypass_depth > 0) return 1;

    ProxyConfig snapshot;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_proxy.enabled && !g_proxy.blocked) {
            return 1;
        }
        if (is_proxy_endpoint(addr) || is_loopback(addr)) {
            return 1;
        }
        if (!is_socket_type_stream(fd)) {
            return 1;
        }
        if (g_proxy.blocked) {
            errno = ECONNREFUSED;
            return -1;
        }
        snapshot = g_proxy;
    }

    // Take the caller's flags so the proxied socket ends up in the same mode it expected.
    int orig_flags = fcntl(fd, F_GETFL, 0);
    bool was_nonblocking = (orig_flags >= 0) && (orig_flags & O_NONBLOCK);

    int proxy_fd = socket(snapshot.addrs.front().ss_family, SOCK_STREAM, 0);
    if (proxy_fd < 0) {
        PLOGE("socket() for proxy failed: %s", strerror(errno));
        errno = ECONNREFUSED;
        return -1;
    }

    struct timeval tv;
    tv.tv_sec = HANDSHAKE_TIMEOUT_SEC;
    tv.tv_usec = 0;
    setsockopt(proxy_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(proxy_fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    bool ok = false;
    for (const auto &stored : snapshot.addrs) {
        socklen_t stored_len = stored.ss_family == AF_INET
                               ? sizeof(struct sockaddr_in)
                               : sizeof(struct sockaddr_in6);
        connect_fn dial = orig_connect ? orig_connect : ::connect;
        if (dial(proxy_fd,
                         reinterpret_cast<const struct sockaddr *>(&stored), stored_len) == 0) {
            ok = true;
            break;
        }
    }
    if (!ok) {
        PLOGE("connect to proxy %s:%d failed: %s", snapshot.host.c_str(), snapshot.port,
              strerror(errno));
        close(proxy_fd);
        errno = ECONNREFUSED;
        return -1;
    }

    if (!socks5_handshake(proxy_fd, addr, snapshot)) {
        close(proxy_fd);
        errno = ECONNREFUSED;
        return -1;
    }

    // Match the blocking mode the caller expects, then hand over the descriptor.
    // The temporary handshake timeouts must not become the guest's stream timeouts.
    for (int option : {SO_SNDTIMEO, SO_RCVTIMEO}) {
        struct timeval original{};
        socklen_t size = sizeof(original);
        getsockopt(fd, SOL_SOCKET, option, &original, &size);
        setsockopt(proxy_fd, SOL_SOCKET, option, &original, sizeof(original));
    }
    int value;
    socklen_t size = sizeof(value);
    if (getsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &value, &size) == 0)
        setsockopt(proxy_fd, IPPROTO_TCP, TCP_NODELAY, &value, size);
    size = sizeof(value);
    if (getsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &value, &size) == 0)
        setsockopt(proxy_fd, SOL_SOCKET, SO_KEEPALIVE, &value, size);
    if (was_nonblocking) {
        int flags = fcntl(proxy_fd, F_GETFL, 0);
        if (flags >= 0) fcntl(proxy_fd, F_SETFL, flags | O_NONBLOCK);
    }

    int descriptor_flags = fcntl(fd, F_GETFD);
    if (dup2(proxy_fd, fd) < 0) {
        PLOGE("dup2 failed: %s", strerror(errno));
        close(proxy_fd);
        errno = ECONNREFUSED;
        return -1;
    }
    if (descriptor_flags >= 0) fcntl(fd, F_SETFD, descriptor_flags);
    close(proxy_fd);

    char target[64];
    if (addr->sa_family == AF_INET) {
        const auto *in = reinterpret_cast<const struct sockaddr_in *>(addr);
        inet_ntop(AF_INET, &in->sin_addr, target, sizeof(target));
        PLOGD("proxied %s:%d via %s", target, ntohs(in->sin_port), snapshot.host.c_str());
    } else {
        const auto *in6 = reinterpret_cast<const struct sockaddr_in6 *>(addr);
        inet_ntop(AF_INET6, &in6->sin6_addr, target, sizeof(target));
        PLOGD("proxied [%s]:%d via %s", target, ntohs(in6->sin6_port), snapshot.host.c_str());
    }
    return 0;
}

static int proxy_connect(int fd, const struct sockaddr *addr, socklen_t len) {
    int result = try_proxy_connect(fd, addr);
    return result == 1 ? orig_connect(fd, addr, len) : result;
}

int tryJavaProxyConnect(int fd, const unsigned char *bytes, int length, int port, int scope) {
    sockaddr_storage target{};
    if (length == 4) {
        auto *ipv4 = reinterpret_cast<sockaddr_in *>(&target);
        ipv4->sin_family = AF_INET;
        ipv4->sin_port = htons(port);
        memcpy(&ipv4->sin_addr, bytes, 4);
    } else if (length == 16) {
        auto *ipv6 = reinterpret_cast<sockaddr_in6 *>(&target);
        ipv6->sin6_family = AF_INET6;
        ipv6->sin6_port = htons(port);
        ipv6->sin6_scope_id = scope;
        memcpy(&ipv6->sin6_addr, bytes, 16);
    } else {
        return 0;
    }
    int result = try_proxy_connect(fd, reinterpret_cast<sockaddr *>(&target));
    if (result < 0) return -errno;
    return result == 0 ? 1 : 0;
}

static bool resolve_proxy_addr(ProxyConfig &config) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *result = nullptr;
    char port_str[8];
    snprintf(port_str, sizeof(port_str), "%d", config.port);
    int rc = getaddrinfo(config.host.c_str(), port_str, &hints, &result);
    if (rc != 0 || result == nullptr) {
        PLOGE("cannot resolve proxy host %s: %s", config.host.c_str(), gai_strerror(rc));
        return false;
    }
    for (struct addrinfo *ai = result; ai != nullptr; ai = ai->ai_next) {
        if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6) continue;
        struct sockaddr_storage stored;
        memset(&stored, 0, sizeof(stored));
        memcpy(&stored, ai->ai_addr, ai->ai_addrlen);
        config.addrs.push_back(stored);
    }
    freeaddrinfo(result);
    return !config.addrs.empty();
}

static void install_hook() {
    if (hook_installed) return;
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == nullptr) {
        PLOGE("cannot open libc.so");
        return;
    }
    void *target = xdl_dsym(handle, "connect", nullptr);
    if (target == nullptr) {
        PLOGE("connect not found in libc");
        xdl_close(handle);
        return;
    }
    if (DobbyHook(target, reinterpret_cast<void *>(proxy_connect),
                  reinterpret_cast<void **>(&orig_connect)) == 0) {
        hook_installed = true;
        PLOGD("connect hooked");
    } else {
        PLOGE("DobbyHook(connect) failed");
    }
    xdl_close(handle);
}

void setProxyBypass(bool bypass) {
    if (bypass) {
        ++g_bypass_depth;
    } else if (g_bypass_depth > 0) {
        --g_bypass_depth;
    }
}

void setProxyConfig(const char *host, int port, const char *user, const char *pass) {
    ProxyConfig config;
    config.blocked = port == -1;
    if (host != nullptr && host[0] != '\0' && port > 0 && port <= 65535) {
        config.enabled = true;
        config.host = host;
        config.port = port;
        if (user != nullptr) config.user = user;
        if (pass != nullptr) config.pass = pass;
        if (!resolve_proxy_addr(config)) {
            PLOGE("proxy %s:%d unavailable, disabling", host, port);
            config.enabled = false;
            config.addrs.clear();
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_proxy = config;
    }

    if (config.blocked) {
        install_hook();
    } else if (config.enabled) {
        install_hook();
        PLOGD("proxy enabled: %s:%d (%zu address(es))", config.host.c_str(), config.port,
              config.addrs.size());
    } else {
        PLOGD("proxy disabled");
    }
}
