#include "hook_impl.h"
#include <cstdio>
#include <cstdarg>
#include <fcntl.h>

extern "C" {

__attribute__((visibility("default")))
FILE* fopen(const char* path, const char* mode) {
    return hook_impl_fopen(path, mode);
}

__attribute__((visibility("default")))
FILE* fopen64(const char* path, const char* mode) {
    return hook_impl_fopen64(path, mode);
}

__attribute__((visibility("default")))
int open(const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }
    return hook_impl_open(pathname, flags, mode);
}

}
