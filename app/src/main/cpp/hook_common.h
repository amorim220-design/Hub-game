#pragma once

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdint>
#include <cstdio>
#include <string>

/**
 * ==============================================================================
 * MECANISMO DE INTERPOSIÇÃO DE SÍMBOLOS NO ANDROID BIONIC
 * ==============================================================================
 *
 * 1. O QUE É E POR QUE FUNCIONA:
 *    No Android, o dynamic linker (/system/bin/linker64) resolve referências
 *    indefinidas de símbolos usando a tabela de símbolos globais (global symbol
 *    namespace). Quando bibliotecas compartilhadas (.so) são carregadas via
 *    System.loadLibrary() ou dlopen(..., RTLD_GLOBAL), os símbolos exportados por
 *    essas bibliotecas são inseridos na tabela de lookup do linker.
 *
 *    Se o jogo ou qualquer biblioteca carregada posteriormente invocar uma
 *    função como "android_dlopen_ext", "fopen" ou "gsl_memory_alloc_pure", o
 *    linker busca da esquerda para a direita na lista de bibliotecas já carregadas.
 *    Como as bibliotecas de hook são carregadas ANTES do código do jogo, a chamada
 *    é desviada para a nossa implementação em vez da libc/libdl do sistema.
 *
 * 2. ORDEM DE CARREGAMENTO OBRIGATÓRIA:
 *    a) libhook_impl.so:
 *       Núcleo dos hooks. Obtém ponteiros reais do sistema via dlsym(RTLD_NEXT)
 *       e armazena as configurações thread-safe (caminho do driver customizado,
 *       pasta de arquivos do jogo e diretório de patches).
 *    b) libmain_hook.so:
 *       Possui dependência ELF (DT_NEEDED) de libhook_impl.so.
 *       Exporta "android_dlopen_ext" e "android_load_sphal_library".
 *    c) libfile_redirect_hook.so:
 *       Possui DT_NEEDED de libhook_impl.so.
 *       Exporta "fopen", "fopen64", "open".
 *    d) libgsl_alloc_hook.so:
 *       Possui DT_NEEDED de libhook_impl.so.
 *       Exporta "gsl_memory_alloc_pure", "gsl_memory_alloc_pure_64", "gsl_memory_free_pure".
 *    e) libcompat_runtime.so:
 *       Runtime principal que apresenta o jogo em janela Vulkan e integra o DXVK.
 *    f) lib<app_engine>.so (Jogo):
 *       Carregado por último. Todas as suas chamadas de dlopen e fopen passam
 *       automaticamente pelos nossos hooks já ativos.
 * ==============================================================================
 */

#define LOG_TAG "VulkanCompatHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

// Funções de configuração pública dos hooks (chamadas via JNI / runtime)
void hook_set_game_dir(const char* dir);
const char* hook_get_game_dir();

void hook_set_custom_driver_path(const char* driver_path);
const char* hook_get_custom_driver_path();

void hook_set_patch_dir(const char* patch_dir);
const char* hook_get_patch_dir();

void hook_set_redirect_enabled(int enabled);
int hook_is_redirect_enabled();

// Implementações reais de interposição chamadas pelas bibliotecas shim
void* hook_impl_android_dlopen_ext(const char* filename, int flags, const void* extinfo, void* caller_addr);
void* hook_impl_android_load_sphal_library(const char* filename, int flags);

FILE* hook_impl_fopen(const char* path, const char* mode);
FILE* hook_impl_fopen64(const char* path, const char* mode);
int hook_impl_open(const char* pathname, int flags, mode_t mode);

void* hook_impl_gsl_memory_alloc_pure(uint32_t size, uint32_t flags);
void* hook_impl_gsl_memory_alloc_pure_64(uint64_t size, uint64_t flags);
void hook_impl_gsl_memory_free_pure(void* ptr);

#ifdef __cplusplus
}
#endif
