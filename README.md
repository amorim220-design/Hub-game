# VulkanCompatShell (D3D9 / Vulkan Compatibility Layer for Android ARM64)

Shell de execução para jogos de PC (Direct3D 9) em dispositivos Android ARM64
(AArch64), baseado em interposição de biblioteca, DXVK (D3D9 → Vulkan) e pacotes de
runtime fornecidos pelo usuário.

> **Este repositório não contém nenhum jogo.** O app é a casca: interposição de
> biblioteca, apresentação Vulkan, opções, instalação de pacotes e diagnóstico.
> O jogo (motor + dados) é trazido pelo usuário.

---

## 1. Arquitetura Geral do Sistema

```
+---------------------------------------------------------------------------------------+
|                              ANDROID (processo do launcher)                             |
|  LauncherActivity (Compose): opções, pacotes de runtime, drivers, diagnóstico          |
|  GameActivity  -> NativeActivity, processo isolado ":game"                             |
+---------------------------------------------------------------------------------------+
        | JNI (NativeBridge)                | dlopen(RTLD_GLOBAL) em JNI_OnLoad
        v                                   v
+---------------------------------------------------------------------------------------+
|  libcompat_runtime.so   ANativeActivity, VkSurface, loop, JNI, DXVK bridge              |
|  libhook_impl.so        núcleo dos hooks (implementação, cache de ponteiros reais)      |
|  libmain_hook.so        exporta android_dlopen_ext / android_load_sphal_library        |
|  libfile_redirect_hook.so  exporta fopen / fopen64 / open  (redireciona "C:\...")       |
|  libgsl_alloc_hook.so   exporta gsl_memory_alloc_pure(_64) / gsl_memory_free_pure       |
+---------------------------------------------------------------------------------------+
        |  o runtime do jogo é carregado depois, com RTLD_GLOBAL
        v
+---------------------------------------------------------------------------------------+
|  PACOTE DE RUNTIME DO USUÁRIO  (.so ARM64: motor + DXVK embutido)                      |
|      pontos de entrada opcionais:  game_main_tick()  ·  pcshim_input_event(event)       |
+---------------------------------------------------------------------------------------+
        |
        v
+---------------------------------------------------------------------------------------+
|  DADOS DO JOGO  (pasta escolhida pelo usuário: trilha, som, modelos, texturas)         |
+---------------------------------------------------------------------------------------+
```

---

## 2. Ordem de carregamento das bibliotecas (o ponto crítico)

No Bionic, a resolução de símbolos é feita por ordem de carga no **escopo global**.
Duas regras determinam se a interposição funciona:

1. **RTLD_GLOBAL é obrigatório.** `System.loadLibrary()` (o caminho normal do Java)
   **não** usa `RTLD_GLOBAL`, então os símbolos exportados ficam no grupo local da
   própria biblioteca e ninguém mais os enxerga.
2. **A primeira carga é a que vale.** No Bionic, carregar de novo uma biblioteca
   já carregada **não** promove ela ao escopo global. Logo, se ela for carregada
   sem `RTLD_GLOBAL` primeiro, a interposição nunca acontece.

Por isso o carregamento **não** é feito pelo Java, e sim em `JNI_OnLoad` de
`libcompat_runtime.so` — o primeiro código nosso a rodar no processo:

```c++
static bool load_hook_libraries() {
    static const char* kHookLibs[] = {
        "libhook_impl.so",          // núcleo
        "libmain_hook.so",          // android_dlopen_ext
        "libfile_redirect_hook.so", // fopen / open
        "libgsl_alloc_hook.so",     // alocadores GSL
    };
    for (const char* name : kHookLibs) {
        dlopen(name, RTLD_NOW | RTLD_GLOBAL);   // <- o GLOBAL é o ponto
    }
}
```

`libhook_impl.so` é a única dessas libs linked em `libcompat_runtime.so`
(`DT_NEEDED`), porque o runtime chama a API dele diretamente
(`hook_set_game_dir`, ...). As outras três **não** podem ser `DT_NEEDED` de
nada: seriam carregadas sem `RTLD_GLOBAL` antes do `dlopen` acima.

### Limitação honesta da interposição

O `libfile_redirect_hook.so` exporta `fopen`/`open`, mas a `libc.so` já está no
escopo global desde o início do processo e é consultada **antes**. A resolução de
símbolo no linker é "primeiro encontrado", então a libc vence.

Consequência prática: o redirecionamento de caminhos só é garantido para
chamadas que partem de bibliotecas **carregadas depois** dos hooks e que não
passem pela libc do sistema. Para ter garantia total, o caminho robusto é:

- **o pacote de runtime chamar a API diretamente** — `libhook_impl.so` exporta
  `hook_set_game_dir()` / `hook_get_game_dir()`, e o runtime resolve com
  `dlsym(RTLD_DEFAULT, "hook_set_game_dir")`; ou
- **injetar `DT_NEEDED` da lib de hook no ELF do runtime** (o `patcher.cpp` já
  reescreve ELFs; a injeção de `DT_NEEDED` é o próximo passo natural).

O estado de tudo isso está em `NativeBridge.hookStatus()` e vai para o
diagnóstico (`native_state.json`).

---

## 3. Pacote de runtime (o que o usuário traz)

ZIP com `meta.json` e a biblioteca do motor:

```
meu_runtime_arm64.zip
├── meta.json
└── libgame_engine.so      (ELF 64-bit, e_machine = 0xB7 / AArch64)
```

```json
{
  "name": "Meu runtime D3D9",
  "version": "1.0.0",
  "author": "comunidade",
  "mainLibrary": "libgame_engine.so"
}
```

Validações do instalador (`PackageInstaller.kt`):

1. **Zip Slip** — entradas com `..` ou caminho canônico fora de
   `files/runtimes/` são rejeitadas.
2. **Tamanho** — limite de descompactação (anti zip bomb).
3. **Arquitetura** — cabeçalho ELF, classe 64-bit, `e_machine = 0xB7`.
   Executáveis PE (`MZ`) e binários x86/x86-64 de PC são **rejeitados com
   mensagem explícita**: o app não emula CPU x86.

### Contrato opcional com o runtime

O runtime pode exportar dois pontos de entrada (ambos opcionais):

```c
void game_main_tick(void);                        // chamado pelo loop do app
int  pcshim_input_event(const AInputEvent* ev);   // recebe teclado/toque/gamepad
```

Sem `game_main_tick`, o runtime conduz o próprio loop e o app só mantém a
janela viva e mede FPS. Sem `pcshim_input_event`, os eventos são apenas
registrados no log.

---

## 4. Driver Vulkan customizado

Drivers otimizados (Mesa Turnip/Freedreno para Adreno, por exemplo) são
instalados como ZIP:

```
turnip_driver.zip
├── meta.json
└── vulkan.adreno.so
```

Quando ativo, o hook `hook_android_dlopen_ext` intercepta o pedido de
`libvulkan.so` e carrega o driver escolhido:

```
loading custom driver: <caminho>
loading default driver: libvulkan.so      (fallback)
```

---

## 5. Patches binários no ELF do motor

`patcher.cpp` lê a biblioteca, valida o cabeçalho e aplica substituições de
bytes em offsets declarados, gravando a cópia patcheada. Nomes suportados:

- `<patch_dir>/<basename>_patched.so` — um conjunto de patches por biblioteca
  (ex.: `libgame_engine.so` → `libgame_engine_patched.so`);
- `<patch_dir>/<n>_patched.so` — layout legado (`1_patched.so`, `2_patched.so`...).

A substituição só acontece para uma biblioteca que **existe de fato** no disco
(caminho pedido ou diretório do runtime), então bibliotecas do sistema nunca são
 sequestradas.

Formato JSON dos patches:

```json
[
  {
    "offset": 524288,
    "expected": "80 1F 00 71",
    "replacement": "1F 20 03 D5",
    "desc": "NOP out DirectSound init check"
  }
]
```

---

## 6. Limitações técnicas

1. **x86 não roda sozinho.** O app não traz emulador de CPU. O motor precisa
   ser recompilado para ARM64 ou vir acompanhado de uma camada de tradução
   (FEX/Box64 + Win32), que é responsabilidade do pacote de runtime.
2. **DXVK não está no repositório.** O upstream do DXVK não tem alvo Android e
   o binário é de terceiros. Ele entra (a) dentro do pacote de runtime do
   usuário ou (b) como `libdxvk.so` pré-compilado para arm64-v8a colocado em
   `third_party/dxvk-prebuilt/lib/arm64-v8a/` (a CI baixa se a variável
   `DXVK_PREBUILT_URL` estiver definida).
3. **Uma VkInstance só.** Se o runtime (DXVK) está ativo, ele é o dono da
   instância e da surface; o app **não** cria uma segunda (a surface pertence
   à instância que a criou).
4. **Ordem de resolução de símbolos** — ver seção 2.
5. **Escopo de armazenamento (Android 11+).** O app usa
   `ACTION_OPEN_DOCUMENT_TREE` e `MANAGE_EXTERNAL_STORAGE` para que os motores
   leiam milhares de arquivos de textura com I/O alto.
6. **Isolamento de processos.** `:gpu_probe` testa o Vulkan sem derrubar a UI;
   `:game` executa o motor isolado.

### Sobre o alocador GSL

`gsl_memory_alloc_pure` / `gsl_memory_alloc_pure_64` /
`gsl_memory_free_pure` pertencem ao **GSL (Games for Windows/Linux)**, o
alocador do motor clássico da EA/Black Box (usado por NFS Underground 2). Não
tem relação com drivers Qualcomm/Adreno. O hook existe porque o jogo é 32-bit
e precisa de blocos alinhados a 64 bytes e limpos em 64 bits no ARM64.

---

## 7. Build

Requisitos: JDK 17, Android SDK (platform 35, build-tools 35, CMake 3.22.1,
NDK 27.2.12479018). O wrapper do Gradle está no repositório.

```bash
# build de teste
./gradlew :app:testDebugUnitTest

# APK debug (arm64-v8a, C++ via CMake/NDK)
./gradlew :app:assembleDebug

# APK release assinado
export KEYSTORE_PATH=my-upload-key.jks
export STORE_PASSWORD=... KEY_PASSWORD=... KEY_ALIAS=upload
./gradlew :app:assembleRelease
```

Sem `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`, o build release usa o
keystore de debug (o APK sai instalável, mas não é o que vai para a Play
Store).

### CI (GitHub Actions)

`.github/workflows/build.yml` faz: checkout → JDK 17 → SDK/NDK/CMake →
keystores → testes → `assembleDebug`/`assembleRelease` → upload dos artefatos.
O APK release já sai assinado pelo AGP (não há passo extra de `apksigner`).

---

## 8. Roteiro do que ainda falta

- [ ] Injeção de `DT_NEEDED` no ELF do runtime, para interposição garantida de
      `fopen`/`open` (hoje ela depende da ordem de resolução de símbolos).
- [ ] `pcshim_input_event` com mapeamento completo (teclado, mouse, gamepad).
- [ ] Patch set pronto para o `WidescreenFix.dat` no formato do motor EA.
- [ ] Pacote de runtime de referência, com DXVK para arm64 empacotado.
- [ ] Teste em dispositivo real (o CI só compila; não há emulador Vulkan com
      surface Android no runner).
