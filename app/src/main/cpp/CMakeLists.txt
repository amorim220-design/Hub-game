cmake_minimum_required(VERSION 3.22.1)

project("vulkan_compat_shell" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Android NDK system libraries
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(VULKAN_LIB vulkan)
find_library(DL_LIB dl)

# 1. libhook_impl.so: Core hook and interception engine
add_library(hook_impl SHARED
    hook_impl.cpp
)
target_include_directories(hook_impl PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(hook_impl
    ${LOG_LIB}
    ${DL_LIB}
)
set_target_properties(hook_impl PROPERTIES
    OUTPUT_NAME "hook_impl"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)

# 2. libmain_hook.so: Exports android_dlopen_ext and android_load_sphal_library
# Has DT_NEEDED of libhook_impl.so so dynamic linker chains the symbols.
add_library(main_hook SHARED
    main_hook.cpp
)
target_link_libraries(main_hook
    hook_impl
    ${LOG_LIB}
    ${DL_LIB}
)
target_link_options(main_hook PRIVATE "-Wl,--no-as-needed")
set_target_properties(main_hook PROPERTIES
    OUTPUT_NAME "main_hook"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)

# 3. libfile_redirect_hook.so: Exports fopen, fopen64, open
add_library(file_redirect_hook SHARED
    file_redirect_hook.cpp
)
target_link_libraries(file_redirect_hook
    hook_impl
    ${LOG_LIB}
)
set_target_properties(file_redirect_hook PROPERTIES
    OUTPUT_NAME "file_redirect_hook"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)

# 4. libgsl_alloc_hook.so: Exports gsl_memory_alloc_pure, gsl_memory_alloc_pure_64, gsl_memory_free_pure
add_library(gsl_alloc_hook SHARED
    gsl_alloc_hook.cpp
)
target_link_libraries(gsl_alloc_hook
    hook_impl
    ${LOG_LIB}
)
set_target_properties(gsl_alloc_hook PROPERTIES
    OUTPUT_NAME "gsl_alloc_hook"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)

# 5. libcompat_runtime.so: Main native runtime, ANativeActivity, Vulkan presentation, DXVK bridge, JNI
add_library(compat_runtime SHARED
    runtime.cpp
    dxvk_bridge.cpp
    patcher.cpp
)

# The hook libraries are NOT linked here on purpose.
# libmain_hook.so / libfile_redirect_hook.so / libgsl_alloc_hook.so export the
# symbols that must be interposed (android_dlopen_ext, fopen, open,
# gsl_memory_alloc_pure...). For the interposition to work they have to be
# loaded with RTLD_GLOBAL and BEFORE anything else loads them: in bionic, a
# library that is already loaded is NOT promoted to the global scope by a
# later dlopen(..., RTLD_GLOBAL). That load is done from JNI_OnLoad
# (runtime.cpp -> load_hook_libraries), which is the first code of ours that
# runs in the process.
#
# libhook_impl.so IS linked here: it holds the implementation (not the
# interposition symbols) and compat_runtime calls into it directly.
target_link_libraries(compat_runtime
    hook_impl
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${VULKAN_LIB}
    ${DL_LIB}
)
set_target_properties(compat_runtime PROPERTIES
    OUTPUT_NAME "compat_runtime"
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)
