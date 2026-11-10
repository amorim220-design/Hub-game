#include "hook_impl.h"
#include <dlfcn.h>

// This library is loaded prior to the game runtime. By exporting these exact
// symbols, the Android dynamic linker (bionic) resolves dlopen invocations
// originating from DT_NEEDED or dlsym calls in the game runtime to our hook functions.

extern "C" {

__attribute__((visibility("default")))
void* android_dlopen_ext(const char* filename, int flags, const void* extinfo, void* caller_addr) {
    return hook_impl_android_dlopen_ext(filename, flags, extinfo, caller_addr);
}

__attribute__((visibility("default")))
void* android_load_sphal_library(const char* filename, int flags) {
    return hook_impl_android_load_sphal_library(filename, flags);
}

}
