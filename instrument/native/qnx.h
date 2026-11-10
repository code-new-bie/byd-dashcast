/* The small part of the QNX 6.5 ARM interface these programs use. The target has no headers,
 * so values come from the system's own binaries. On Linux the real headers are used for tests. */
#ifndef DASHCAST_QNX_H
#define DASHCAST_QNX_H

#ifdef __linux__
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/mman.h>
#include <sys/socket.h>
#define POLL_FAILED (POLLERR | POLLHUP | POLLNVAL)
static inline int TimerTimeout(int clock, int flags, const void *notify, const unsigned long long *limit, unsigned long long *previous)
{
    (void)clock; (void)flags; (void)notify; (void)limit; (void)previous;
    return 0;
}
/* The decoder's input: QNX message passing, supplied by a test fixture on Linux. */
int name_open(const char *name, int flags);
int name_close(int connection);
int MsgSendvs_r(int connection, const void *send_parts, int send_count, void *reply, int reply_size);
#else

typedef unsigned int size_t;
typedef int ssize_t;
typedef int pid_t;
typedef unsigned int mode_t;
typedef void (*sighandler_t)(int);

struct timespec { long tv_sec; long tv_nsec; };
struct pollfd { int fd; short events; short revents; };

enum { ENOENT = 2, EINTR = 4, EAGAIN = 11, EEXIST = 17 };
enum { O_RDONLY = 0, O_WRONLY = 1, O_RDWR = 2, O_NONBLOCK = 0x80, O_CREAT = 0x100, O_TRUNC = 0x200 };
enum { F_OK = 0, X_OK = 1 };
enum { F_SETFD = 2, F_SETFL = 4, FD_CLOEXEC = 1 };
enum { SIGHUP = 1, SIGINT = 2, SIGKILL = 9, SIGPIPE = 13, SIGTERM = 15 };
enum { POLLIN = 5, POLLOUT = 2, POLL_FAILED = 0x1060 };
enum { AF_INET = 2, SOCK_STREAM = 1, SOL_SOCKET = 0xffff, SO_REUSEADDR = 4, IPPROTO_TCP = 6, TCP_NODELAY = 1 };
typedef unsigned socklen_t;
struct in_addr { unsigned s_addr; };
struct sockaddr { unsigned char sa_len, sa_family; char sa_data[14]; };
struct sockaddr_in { unsigned char sin_len, sin_family; unsigned short sin_port; struct in_addr sin_addr; char sin_zero[8]; };
enum { WNOHANG = 0x40, CLOCK_MONOTONIC = 2, PATH_MAX = 1024, RTLD_NOW = 2, RTLD_GLOBAL = 0x100, RTLD_LOCAL = 0x200 };
enum { PROT_READ = 0x100, PROT_WRITE = 0x200, MAP_SHARED = 1, SEEK_SET = 0, SEEK_END = 2 };
#define SIG_DFL ((sighandler_t)0)
#define SIG_IGN ((sighandler_t)1)
#define NULL ((void *)0)
#define errno (*__get_errno_ptr())
#define WIFEXITED(status) (((status) & 0xff) == 0)
#define WEXITSTATUS(status) (((status) >> 8) & 0xff)

int *__get_errno_ptr(void);
void exit(int code) __attribute__((noreturn));
void _exit(int code) __attribute__((noreturn));
int open(const char *path, int flags, ...);
int close(int fd);
int dup2(int from, int to);
int access(const char *path, int mode);
ssize_t readlink(const char *path, char *target, size_t size);
int setenv(const char *name, const char *value, int replace);
int unsetenv(const char *name);
int rename(const char *from, const char *to);
mode_t umask(mode_t mask);
ssize_t read(int fd, void *data, size_t size);
ssize_t write(int fd, const void *data, size_t size);
int pipe(int fds[2]);
int fcntl(int fd, int command, ...);
int poll(struct pollfd *fds, unsigned count, int timeout_ms);
int mkdir(const char *path, mode_t mode);
int rmdir(const char *path);
int mkfifo(const char *path, mode_t mode);
int unlink(const char *path);
char *realpath(const char *path, char *resolved);
pid_t fork(void);
int execv(const char *path, char *const argv[]);
pid_t waitpid(pid_t pid, int *status, int options);
int kill(pid_t pid, int signal_number);
sighandler_t signal(int signal_number, sighandler_t handler);
int nanosleep(const struct timespec *request, struct timespec *remaining);
int clock_gettime(int clock, struct timespec *now);
int TimerTimeout(int clock, int flags, const void *notify, const unsigned long long *limit, unsigned long long *previous);
unsigned alarm(unsigned seconds);
pid_t getpid(void);
int usleep(unsigned microseconds);
void *malloc(size_t size);
void free(void *data);
void *memcpy(void *to, const void *from, size_t size);
int socket(int family, int type, int protocol);
int setsockopt(int fd, int level, int option, const void *value, socklen_t size);
int bind(int fd, const struct sockaddr *address, socklen_t size);
int listen(int fd, int backlog);
int accept(int fd, struct sockaddr *address, socklen_t *size);
ssize_t recv(int fd, void *data, size_t size, int flags);
ssize_t send(int fd, const void *data, size_t size, int flags);
int inet_pton(int family, const char *text, void *address);
void *mmap(void *address, size_t size, int protection, int flags, int fd, long offset);
int munmap(void *address, size_t size);
long lseek(int fd, long offset, int whence);
int name_open(const char *name, int flags);
int name_close(int connection);
int MsgSendvs_r(int connection, const void *send_parts, int send_count, void *reply, int reply_size);
void *dlopen(const char *name, int flags);
void *dlsym(void *library, const char *name);
int dlclose(void *library);
void *memset(void *data, int value, size_t size);
void *memchr(const void *data, int value, size_t size);
int memcmp(const void *a, const void *b, size_t size);
size_t strlen(const char *text);
char *strchr(const char *text, int wanted);
char *strrchr(const char *text, int wanted);
char *strstr(const char *text, const char *wanted);
int strcmp(const char *a, const char *b);
int strncmp(const char *a, const char *b, size_t size);
unsigned long strtoul(const char *text, char **end, int base);
int snprintf(char *buffer, size_t size, const char *format, ...);
#endif

#endif
