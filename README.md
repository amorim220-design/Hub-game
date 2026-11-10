# VulkanCompatShell (D3D9 / Vulkan Compatibility Layer for Android ARM64)

Compatibilidade e shell de execução para jogos de PC (Direct3D 9) em dispositivos Android ARM64 (AArch64), baseado na arquitetura de interposição de bibliotecas com Vulkan e DXVK.

---

## 1. Arquitetura Geral do Sistema (ASCII Diagram)

```
+---------------------------------------------------------------------------------------+
|                                    ANDROID SYSTEM                                     |
|  +---------------------------------------------------------------------------------+  |
|  | PROCESS: com.aistudio.vulkancompat.qznvwt (Launcher UI - Jetpack Compose)       |  |
|  |   - PackageInstaller (Zip Slip Safe, ELF ARM64 Enforcement)                     |  |
|  |   - DriverInstaller (Custom Vulkan Drivers Mesa Turnip/Adreno)                  |  |
|  |   - OptionsManager (Resolutions, FPS Limit, Widescreen HUD, Minimap, Shaders)   |  |
|  |   - Diagnostics (Telemetry, Logcat capture, .zip generation)                   |  |
|  +---------------------------------------------------------------------------------+  |
|                                         |                                             |
|                     IPC / SharedPreferences / Intent Extras                           |
|                                         v                                             |
|  +-------------------------------------+   +---------------------------------------+  |
|  | PROCESS: :gpu_probe                 |   | PROCESS: :game (Isolated Native Game) |  |
|  |  - GpuProbeActivity                 |   |  - GameActivity (NativeActivity)      |  |
|  |  - NativeBridge.probeVulkanGpu()    |   |  - ANativeActivity_onCreate           |  |
|  |  (Tests Vulkan 1.1 safely without   |   |  - Background Game Loop Thread (TID)  |  |
|  |   crashing the Launcher UI)         |   |  - Touch / Gamepad / Keyboard Dispatch|  |
|  +-------------------------------------+   +---------------------------------------+  |
|                                                        |                              |
|                                        Dynamic Linker Interposition                   |
|                                                        v                              |
|  +---------------------------------------------------------------------------------+  |
|  |                   C / C++ NATIVE INTERPOSITION LAYER (ARM64)                    |  |
|  |                                                                                 |  |
|  |  [libmain_hook.so]                                                              |  |
|  |    Exports: android_dlopen_ext, android_load_sphal_library                      |  |
|  |    DT_NEEDED: libhook_impl.so                                                   |  |
|  |                                                                                 |  |
|  |  [libfile_redirect_hook.so]                                                     |  |
|  |    Exports: fopen, fopen64, open (Redirects "C:\..." -> /storage/.../game_dir)  |  |
|  |    DT_NEEDED: libhook_impl.so                                                   |  |
|  |                                                                                 |  |
|  |  [libgsl_alloc_hook.so]                                                         |  |
|  |    Exports: gsl_memory_alloc_pure, gsl_memory_alloc_pure_64, gsl_memory_free_pure|  |
|  |    DT_NEEDED: libhook_impl.so (64-bit aligned safe driver memory allocation)    |  |
|  |                                                                                 |  |
|  |  [libhook_impl.so]                                                              |  |
|  |    - Symbol Cache (dlsym RTLD_NEXT: real_android_dlopen_ext, real_fopen, etc.)    |  |
|  |    - Vulkan Driver Interceptor:                                                 |  |
|  |        "libvulkan.so" requested -> loads custom driver (%s%s) or default (%s)   |  |
|  |    - Binary Patcher Interceptor:                                                |  |
|  |        "%s/%d_patched.so" -> loads patched engine elf                           |  |
|  |                                                                                 |  |
|  |  [libcompat_runtime.so]                                                         |  |
|  |    - ANativeWindow Surface Management                                           |  |
|  |    - DXVK Bridge (dxvkAndroidSetWindow, dxvkAndroidSetRenderSize, etc.)         |  |
|  |    - DXVK Configuration generator (dxvk.conf, vulkan_pipelines.bin)             |  |
|  |    - Vulkan 1.1 Instance & VkSurfaceKHR Creation                                |  |
|  +---------------------------------------------------------------------------------+  |
|                                         |                                             |
|                                         v                                             |
|  +---------------------------------------------------------------------------------+  |
|  | USER GAME RUNTIME (.so ARM64, ex.: Recompiled D3D9 Engine + Embedded DXVK)      |  |
|  +---------------------------------------------------------------------------------+  |
|                                         |                                             |
|                                         v                                             |
|  +---------------------------------------------------------------------------------+  |
|  | GAME ASSETS DIRECTORY (User provided PC files: tracks, sound, models, textures) |  |
|  +---------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------+
```

---

## 2. Ordem Crítica de Carregamento das Bibliotecas

No Android (Bionic libc/linker), os símbolos são resolvidos com prioridade na ordem em que as bibliotecas compartilhadas são carregadas no espaço de endereçamento global (`RTLD_GLOBAL` / `System.loadLibrary`).

Para que a interposição de símbolos funcione de forma transparente e infalível, as bibliotecas **DEVEM** ser carregadas exatamente nesta ordem:

1. `libhook_impl.so`:
   Inicializa a estrutura interna de sincronização, armazena caminhos da pasta do jogo, driver customizado e localiza os ponteiros originais do sistema (`libc.so`, `libdl.so`) via `RTLD_NEXT` e `dlsym`.
2. `libmain_hook.so`:
   Possui dependência ELF direta (`DT_NEEDED`) de `libhook_impl.so` e exporta com visibilidade padrão `android_dlopen_ext` e `android_load_sphal_library`. Quando qualquer código nativo subsequente invoca `android_dlopen_ext`, o Bionic resolve para a nossa função exportada.
3. `libfile_redirect_hook.so`:
   Exporta `fopen`, `fopen64` e `open`. Intercepta todas as tentativas do jogo de abrir caminhos legados do Windows (como `C:\Games\NFS\Tracks\...` ou caminhos relativos com barra invertida `\`) e os traduz para o diretório de dados do jogo montado no Android.
4. `libgsl_alloc_hook.so`:
   Exporta alocadores do subsistema GSL da Qualcomm/Adreno (`gsl_memory_alloc_pure`, `gsl_memory_alloc_pure_64`, `gsl_memory_free_pure`) garantindo alinhamento de 64 bytes e limites seguros de memória de 64 bits para motores de jogos legados de 32 bits.
5. `libcompat_runtime.so`:
   Runtime principal com ciclo de vida `ANativeActivity`, integração com o DXVK Bridge, criação da superfície Vulkan (`VkSurfaceKHR`) e loop do jogo.
6. `lib<app_runtime>.so` (Pacote de Runtime do Usuário):
   Carregado por último. Ao carregar, todas as suas chamadas para `dlopen("libvulkan.so")` e `fopen(...)` são automaticamente filtradas e tratadas pelos nossos hooks.

---

## 3. Como Cadastrar e Empacotar um Pacote de Runtime

O pacote de runtime deve ser fornecido pelo usuário em formato ZIP sem dados comerciais.

### Estrutura interna do ZIP:
```
meu_runtime_arm64.zip
├── meta.json
└── libgame_engine.so
```

### Exemplo de `meta.json`:
```json
{
  "name": "DXVK D3D9 Runtime",
  "version": "1.10.3",
  "author": "Community Port",
  "mainLibrary": "libgame_engine.so"
}
```

### Regras de Validação do Instalador:
1. **Zip Slip Protection**: Rejeita qualquer entrada contendo sequências `..` ou caminhos canônicos que tentem escapar do diretório de sandbox do aplicativo (`/data/user/0/<package>/files/runtimes/`).
2. **Limite de Tamanho**: Limite padrão de 500 MB descompactado para evitar Zip Bombs.
3. **Validação ELF ARM64**:
   - Cabeçalho ELF validado: `\x7fELF`.
   - Classe ELF: 64-bit (`EI_CLASS = 2`).
   - Arquitetura: `e_machine = 0x00B7` (AArch64 / ARM64).
   - Executáveis PE de Windows (`.exe` / `.dll` com cabeçalho `MZ`) ou binários x86 / x86_64 de PC são **rejeitados com mensagem explicativa** orientando o usuário a recompilar ou fornecer a camada tradutora ARM64.

---

## 4. Como Cadastrar um Driver Vulkan Customizado

Drivers Vulkan otimizados para GPU Adreno (como builds do **Mesa Turnip / Freedreno**) podem ser instalados via ZIP:

```
turnip_v24_driver.zip
├── meta.json
└── vulkan.adreno.so
```

### Exemplo de `meta.json`:
```json
{
  "name": "Mesa Turnip Driver",
  "version": "24.2.0-devel",
  "author": "Freedreno",
  "minVulkanVersion": "1.1",
  "driverFileName": "vulkan.adreno.so"
}
```

Quando ativado na aba **Drivers Vulkan**, o hook `hook_android_dlopen_ext` intercepta solicitações para `libvulkan.so` e carrega o driver customizado selecionado.

---

## 5. Como Aplicar Patches Binários ao ELF do Jogo

O sistema inclui o módulo `patcher.cpp` que lê a biblioteca ELF do motor, valida o cabeçalho e aplica substituições de bytes diretamente em offsets pré-determinados (ex.: ajustes de FOV widescreen, remoção de chamadas legadas a DirectDraw ou ajustes de memória), gerando um arquivo de cache executável:
`%s/%d_patched.so`

### Formato JSON para Patches:
```json
[
  {
    "offset": 524288,
    "expected": "80 1F 00 71",
    "replacement": "1F 20 03 D5",
    "desc": "NOP out DirectSound init check"
  },
  {
    "offset": 1048576,
    "expected": "00 00 80 3F",
    "replacement": "39 8E E3 3F",
    "desc": "Set aspect ratio constant to 16:9 (1.777f)"
  }
]
```

---

## 6. Limitações Técnicas & Notas de Risco

1. **Instruções x86 não rodam diretamente em ARM64**:
   O aplicativo não inclui emulador de CPU x86 de propósito comercial. O código do jogo deve ser recompilado nativamente para ARM64 (`aarch64-linux-android`) ou integrado a um tradutor dinâmico ARM64 compatível com a plataforma Android.
2. **Compatibilidade de Drivers Vulkan Proprietary (Qualcomm vs MediaTek/Mali)**:
   A interposição de driver customizado (ex.: Mesa Turnip) funciona primariamente em chipsets Qualcomm Snapdragon com GPUs Adreno 6xx/7xx. Dispositivos com GPUs ARM Mali ou PowerVR devem usar o driver padrão do sistema.
3. **Scoped Storage no Android 11+ (API 30+)**:
   O shell implementa tanto permissões de acesso com fallback para o Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) quanto `MANAGE_EXTERNAL_STORAGE` para permitir que motores com centenas de arquivos de texturas leiam dados com alta taxa de I/O diretamente do sistema de arquivos.
4. **Isolamento de Processos**:
   - `:gpu_probe`: executa sondas Vulkan isoladas de modo que qualquer travamento no driver proprietário não encerre a interface de usuário.
   - `:game`: executa o motor do jogo como `NativeActivity`, isolando o estado e evitando vazamento de memória para a UI do Launcher.

---

## 7. Comandos de Build Local

Para compilar o projeto em sua máquina local Linux / macOS:

```bash
# 1. Definir variáveis de ambiente
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

# 2. Executar testes locais unitários e Robolectric
gradle :app:testDebugUnitTest

# 3. Compilar APK Debug (arm64-v8a com C++ CMake e NDK)
gradle :app:assembleDebug

# 4. Compilar APK Release assinado
gradle :app:assembleRelease
```
