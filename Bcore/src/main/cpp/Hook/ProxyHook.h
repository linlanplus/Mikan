#ifndef BLACKBOX_PROXYHOOK_H
#define BLACKBOX_PROXYHOOK_H

// Per-clone SOCKS5 proxying. The guest process calls setProxy() once at startup with the
// clone's proxy; from then on every outbound TCP connect() is rerouted through it.
//
// Only TCP is handled. DNS still resolves on the device and UDP goes out directly, which is
// enough to move a clone's visible IP; it is not a leak-proof tunnel.

// Empty host clears the proxy for this process.
void setProxyConfig(const char *host, int port, const char *user, const char *pass);
int tryJavaProxyConnect(int fd, const unsigned char *bytes, int length, int port, int scope);

// Callers wrap the proxy's own outbound connection in begin/end so the hook leaves it alone.
void setProxyBypass(bool bypass);

#endif
