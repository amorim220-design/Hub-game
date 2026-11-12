# VulkanCompatShell (D3D9 / Vulkan Compatibility Layer for Android ARM64)

Shell de execução para jogos de PC (Direct3D 9) em dispositivos Android ARM64
(AArch64), baseado em interposição de biblioteca, DXVK (D3D9 → Vulkan) e pacotes de
runtime fornecidos pelo usuário.

> **Este repositório não contém nenhum jogo.** O app é a casca: interposição de
> biblioteca, apresentação Vulkan, opções, instalação de pacotes e diagnóstico.
> O jogo (motor + dados) é trazido pelo usuário.

---

## Foco do Hub-game e caminhos de execução

O repositório mantém duas formas diferentes de executar jogos:

- **`runner/` (Winlator)**: usa Wine e Box64 para carregar jogos Windows. É o caminho mais abrangente para testar jogos de PC, mas cada título precisa de um perfil e pode ter custos de desempenho ou incompatibilidades.
- **`app/` (VulkanCompatShell)**: carrega um motor recompilado para ARM64 como biblioteca Android. Pode reduzir a sobrecarga, mas exige uma recompilação e integração específicas para cada jogo; não executa qualquer arquivo `.exe`.

Para o objetivo de compatibilidade ampla, comece pelo runner e mantenha a opção nativa apenas para jogos que tenham um port ARM64 funcional. A escolha deve ser feita por jogo, medindo estabilidade, resposta dos controles, imagem e desempenho em aparelhos reais. Nenhum dos dois caminhos garante ausência de engasgos em todos os jogos.

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

O app **não traz o jogo**. O pacote de runtime é o que sabe rodá-lo, e há dois
tipos (detalhes completos em [`docs/runtime-package.md`](docs/runtime-package.md)):

**Tipo `native` (modelo A)** — o pacote traz uma `.so` ARM64 que já é o motor
recompilado, com o DXVK embutido:

```
meu_runtime.zip
├── meta.json
└── libgame_engine.so     (ELF 64-bit, e_machine = 0xB7 / AArch64)
```

Contrato opcional com o runtime:

```c
void game_main_tick(void);                       // chamado pelo loop do app
int  pcshim_input_event(const AInputEvent* ev);  // recebe teclado/toque/gamepad
```

**Tipo `wine` (modelo B)** — o pacote traz um prefixo do Wine + tradutor de CPU
(Box64/FEX) + DXVK, e o app o executa como **processo separado**, trocando
frames por um framebuffer compartilhado e input por um socket UNIX:

```json
{
  "type": "wine",
  "prefix": "prefix",
  "exe": "drive_c/jogo/Game.exe",
  "launcher": "bin/box64",
  "launcherArgs": ["wine64"],
  "displayWidth": 1280,
  "displayHeight": 720
}
```

Validações do instalador (`PackageInstaller.kt`):

1. **Zip Slip** — entradas com `..` ou caminho canônico fora de
   `files/runtimes/` são rejeitadas.
2. **Tamanho** — limite de descompactação (anti zip bomb).
3. **Arquitetura** — cabeçalho ELF, classe 64-bit, `e_machine = 0xB7`.
   Executáveis PE (`MZ`) e binários x86/x86-64 de PC são **rejeitados com
   mensagem explícita**: o app não emula CPU x86.
4. **Coerência do pacote** — prefixo, executável e lançador precisam existir
   dentro do pacote (`RuntimeSpec.validateFiles`).

### Pacote de referência (para testar sem Wine)

```bash
./tools/guest/build.sh        # compila o guest de exemplo (precisa do NDK)
./tools/guest/make-package.sh # gera o .zip para instalar no app
```

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

Para uma release distribuível, configure uma chave estável em
`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` e `KEY_ALIAS`.
Sem ela, não distribua um APK release assinado com a chave de debug.

### CI (GitHub Actions)

`.github/workflows/build.yml` executa os testes como etapa obrigatória e sempre
gera o APK debug. O APK release só é gerado quando estão configurados os
segredos `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_PASSWORD` e `RELEASE_KEY_ALIAS`. Assim a CI não cria uma chave
temporária que mudaria a assinatura em cada build. O teste de GPU e o teste de
jogos ainda precisam ser feitos em aparelhos reais.

---

## 8. Roteiro do que ainda falta

- [ ] Display acelerado para o modelo B: hoje o guest entrega um framebuffer de
      CPU. Com DXVK (Vulkan) o caminho certo é exportar uma `VkImage` do host
      (`VK_KHR_external_memory_fd`) em vez de copiar pixels.
- [ ] `pcshim_input_event` com mapeamento completo (teclado, mouse, gamepad).
- [ ] Injeção de `DT_NEEDED` no ELF do runtime, para interposição garantida de
      `fopen`/`open` (hoje ela depende da ordem de resolução de símbolos).
- [ ] Patch set pronto para o `WidescreenFix.dat` no formato do motor EA.
- [ ] Pacote de runtime de referência com DXVK/VKWIND empacotado.
- [ ] Teste em dispositivo real (o CI só compila; não há emulador Vulkan com
      surface Android no runner).
