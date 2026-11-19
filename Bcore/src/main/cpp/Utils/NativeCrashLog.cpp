#include "NativeCrashLog.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

// Writes a short report of a fatal native signal into the process's FileLog file, then hands the
// signal on to the previous handler (debuggerd), so the system still writes its tombstone. ROMs
// that encrypt or filter logcat leave the bb_logs file as the only trace of a native crash.
//
// Everything below runs inside a signal handler: no malloc, no stdio, no locks. Addresses are
// resolved by reading /proc/self/maps with plain syscalls instead of dladdr, which takes the
// linker lock another thread may be holding.

namespace {

const int kSignals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGILL, SIGFPE, SIGTRAP, SIGSYS};
constexpr int kMaxFrames = 20;

char g_path[512];
long g_utc_offset;  // seconds east of UTC, sampled at install time
struct sigaction g_previous[NSIG];
std::atomic<bool> g_reporting{false};

struct Line {
    char buf[640];
    size_t len;

    void add(const char *s) {
        while (*s && len < sizeof(buf) - 1) buf[len++] = *s++;
    }

    void add(const char *s, size_t n) {
        for (size_t i = 0; i < n && len < sizeof(buf) - 1; i++) buf[len++] = s[i];
    }

    void addDec(unsigned long v, int width = 0) {
        char tmp[24];
        int n = 0;
        do {
            tmp[n++] = static_cast<char>('0' + v % 10);
            v /= 10;
        } while (v != 0);
        while (n < width) tmp[n++] = '0';
        while (n > 0 && len < sizeof(buf) - 1) buf[len++] = tmp[--n];
    }

    void addHex(uintptr_t v) {
        char tmp[20];
        int n = 0;
        do {
            tmp[n++] = "0123456789abcdef"[v & 0xf];
            v >>= 4;
        } while (v != 0);
        add("0x");
        while (n > 0 && len < sizeof(buf) - 1) buf[len++] = tmp[--n];
    }
};

const char *signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS: return "SIGBUS";
        case SIGABRT: return "SIGABRT";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        case SIGSYS: return "SIGSYS";
        default: return "signal";
    }
}

// Starts a line in FileLog's format: "MM-dd HH:mm:ss.SSS pid tid E NativeCrash: ".
void beginLine(Line &line) {
    line.len = 0;
    timespec now{};
    clock_gettime(CLOCK_REALTIME, &now);
    long long local = static_cast<long long>(now.tv_sec) + g_utc_offset;
    long long days = local / 86400;
    long secs = static_cast<long>(local % 86400);
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    days += 719468;
    long long era = days / 146097;
    unsigned doe = static_cast<unsigned>(days - era * 146097);
    unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    unsigned day = doy - (153 * mp + 2) / 5 + 1;
    unsigned month = mp < 10 ? mp + 3 : mp - 9;
    line.addDec(month, 2);
    line.add("-");
    line.addDec(day, 2);
    line.add(" ");
    line.addDec(secs / 3600, 2);
    line.add(":");
    line.addDec(secs / 60 % 60, 2);
    line.add(":");
    line.addDec(secs % 60, 2);
    line.add(".");
    line.addDec(now.tv_nsec / 1000000, 3);
    line.add(" ");
    line.addDec(static_cast<unsigned long>(getpid()));
    line.add(" ");
    line.addDec(static_cast<unsigned long>(syscall(SYS_gettid)));
    line.add(" E NativeCrash: ");
}

void writeLine(int fd, Line &line) {
    line.add("\n");
    if (fd >= 0) write(fd, line.buf, line.len);
}

uintptr_t hexValue(const char *&p) {
    uintptr_t v = 0;
    for (;; p++) {
        char c = *p;
        if (c >= '0' && c <= '9') v = (v << 4) | static_cast<uintptr_t>(c - '0');
        else if (c >= 'a' && c <= 'f') v = (v << 4) | static_cast<uintptr_t>(c - 'a' + 10);
        else break;
    }
    return v;
}

struct Mapping {
    char path[256];
    uintptr_t start;
    uintptr_t offset;
    bool found;
};

// Parses one maps line ("start-end perms offset dev inode path") and fills the slots whose address
// falls inside it.
void matchMapsLine(const char *line, const uintptr_t *addrs, Mapping *out, int count) {
    const char *p = line;
    uintptr_t start = hexValue(p);
    if (*p != '-') return;
    p++;
    uintptr_t end = hexValue(p);
    bool any = false;
    for (int i = 0; i < count; i++) {
        if (!out[i].found && addrs[i] >= start && addrs[i] < end) any = true;
    }
    if (!any) return;
    while (*p == ' ') p++;
    while (*p && *p != ' ') p++;  // perms
    while (*p == ' ') p++;
    uintptr_t offset = hexValue(p);
    for (int field = 0; field < 2; field++) {  // dev, inode
        while (*p == ' ') p++;
        while (*p && *p != ' ') p++;
    }
    while (*p == ' ') p++;
    for (int i = 0; i < count; i++) {
        if (out[i].found || addrs[i] < start || addrs[i] >= end) continue;
        out[i].found = true;
        out[i].start = start;
        out[i].offset = offset;
        size_t n = 0;
        while (p[n] && p[n] != '\n' && n < sizeof(out[i].path) - 1) {
            out[i].path[n] = p[n];
            n++;
        }
        out[i].path[n] = '\0';
    }
}

// Only the first crashing thread gets past g_reporting, so these can live outside the signal stack,
// which is small.
char g_chunk[1024];
char g_mapsLine[512];
Mapping g_regMaps[2];
Mapping g_frameMaps[kMaxFrames];

void resolve(const uintptr_t *addrs, Mapping *out, int count) {
    for (int i = 0; i < count; i++) out[i].found = false;
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char *chunk = g_chunk;
    char *line = g_mapsLine;
    size_t used = 0;
    ssize_t n;
    while ((n = read(fd, chunk, sizeof(g_chunk))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (chunk[i] == '\n') {
                line[used] = '\0';
                matchMapsLine(line, addrs, out, count);
                used = 0;
            } else if (used < sizeof(g_mapsLine) - 1) {
                line[used++] = chunk[i];
            }
        }
    }
    close(fd);
}

void addLocation(Line &line, uintptr_t addr, const Mapping &m) {
    line.addHex(addr);
    if (!m.found) {
        line.add(" (unmapped)");
        return;
    }
    line.add(" ");
    line.add(m.path[0] ? m.path : "[anon]");
    line.add("+");
    line.addHex(addr - m.start + m.offset);
}

void addThreadName(Line &line) {
    char path[64];
    Line p{};
    p.add("/proc/self/task/");
    p.addDec(static_cast<unsigned long>(syscall(SYS_gettid)));
    p.add("/comm");
    memcpy(path, p.buf, p.len);
    path[p.len] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char name[32];
    ssize_t n = read(fd, name, sizeof(name) - 1);
    close(fd);
    if (n <= 0) return;
    while (n > 0 && (name[n - 1] == '\n' || name[n - 1] == '\0')) n--;
    line.add(" in thread \"");
    line.add(name, static_cast<size_t>(n));
    line.add("\"");
}

// Return addresses from the frame-pointer chain (arm64 keeps x29 as frame pointer). Stops at the
// first record that does not look like a stack frame above the current sp.
int walkFrames(const ucontext_t *uc, uintptr_t *frames, int max) {
    int count = 0;
#if defined(__aarch64__)
    uintptr_t sp = uc->uc_mcontext.sp;
    uintptr_t fp = uc->uc_mcontext.regs[29];
    while (count < max) {
        if (fp == 0 || (fp & 0xf) != 0 || fp < sp || fp - sp > 8 * 1024 * 1024) break;
        auto *record = reinterpret_cast<const uintptr_t *>(fp);
        uintptr_t next = record[0];
        uintptr_t ret = record[1] & 0x0000ffffffffffffULL;  // drop PAC / tag bits
        if (ret == 0) break;
        frames[count++] = ret;
        if (next <= fp) break;
        fp = next;
    }
#else
    (void) uc;
    (void) frames;
    (void) max;
#endif
    return count;
}

void report(int sig, siginfo_t *info, void *context) {
    auto *uc = static_cast<ucontext_t *>(context);
    int fd = open(g_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
    Line line{};

    beginLine(line);
    line.add("signal ");
    line.addDec(static_cast<unsigned long>(sig));
    line.add(" (");
    line.add(signalName(sig));
    line.add("), code ");
    if (info->si_code < 0) {
        line.add("-");
        line.addDec(static_cast<unsigned long>(-info->si_code));
    } else {
        line.addDec(static_cast<unsigned long>(info->si_code));
    }
    line.add(", fault addr ");
    line.addHex(reinterpret_cast<uintptr_t>(info->si_addr));
    addThreadName(line);
    writeLine(fd, line);

    uintptr_t regs[2] = {0, 0};
#if defined(__aarch64__)
    regs[0] = uc->uc_mcontext.pc;
    regs[1] = uc->uc_mcontext.regs[30] & 0x0000ffffffffffffULL;
#elif defined(__arm__)
    regs[0] = uc->uc_mcontext.arm_pc;
    regs[1] = uc->uc_mcontext.arm_lr;
#endif
    Mapping *regMaps = g_regMaps;
    resolve(regs, regMaps, 2);
    const char *regNames[2] = {"  pc ", "  lr "};
    for (int i = 0; i < 2; i++) {
        beginLine(line);
        line.add(regNames[i]);
        addLocation(line, regs[i], regMaps[i]);
        writeLine(fd, line);
    }

    // Written last: a corrupt frame chain can fault again, and the lines above are already out.
    uintptr_t frames[kMaxFrames];
    int count = walkFrames(uc, frames, kMaxFrames);
    if (count > 0) {
        Mapping *frameMaps = g_frameMaps;
        resolve(frames, frameMaps, count);
        for (int i = 0; i < count; i++) {
            beginLine(line);
            line.add("  #");
            line.addDec(static_cast<unsigned long>(i), 2);
            line.add(" ");
            addLocation(line, frames[i], frameMaps[i]);
            writeLine(fd, line);
        }
    }
    if (fd >= 0) close(fd);
}

void handler(int sig, siginfo_t *info, void *context) {
    bool expected = false;
    if (g_reporting.compare_exchange_strong(expected, true)) {
        report(sig, info, context);
    }
    // Put the previous handler back. A fault re-executes the faulting instruction on return and
    // reaches it with the original siginfo; a signal that was sent (abort, tgkill) is sent again
    // and delivered once this handler returns.
    sigaction(sig, &g_previous[sig], nullptr);
    if (info->si_code <= 0 || sig == SIGABRT) {
        syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), sig);
    }
}

}  // namespace

void installNativeCrashLog(const char *path) {
    if (path == nullptr || strlen(path) >= sizeof(g_path)) return;
    strcpy(g_path, path);
    time_t now = time(nullptr);
    tm local{};
    localtime_r(&now, &local);
    g_utc_offset = local.tm_gmtoff;

    struct sigaction action{};
    action.sa_sigaction = handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    for (int sig : kSignals) {
        sigaction(sig, &action, &g_previous[sig]);
    }
}
