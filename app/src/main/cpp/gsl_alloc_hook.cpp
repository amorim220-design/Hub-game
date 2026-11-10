#include "hook_impl.h"
#include <cstdint>

extern "C" {

__attribute__((visibility("default")))
void* gsl_memory_alloc_pure(uint32_t size, uint32_t flags) {
    return hook_impl_gsl_memory_alloc_pure(size, flags);
}

__attribute__((visibility("default")))
void* gsl_memory_alloc_pure_64(uint64_t size, uint64_t flags) {
    return hook_impl_gsl_memory_alloc_pure_64(size, flags);
}

__attribute__((visibility("default")))
void gsl_memory_free_pure(void* ptr) {
    hook_impl_gsl_memory_free_pure(ptr);
}

}
