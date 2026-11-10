# Pacotes de runtime

O app não traz o jogo. Ele traz a *casca*: o pacote de runtime é o que sabe
rodar o jogo. Este documento descreve os dois formatos suportados.

---

## 1. `meta.json`

Todo pacote tem um `meta.json` na raiz. É ele que decide como o jogo é iniciado.

```json
{
  "type": "native",
  "name": "Meu runtime",
  "version": "1.0.0",
  "author": "comunidade",

  "mainLibrary": "libgame_engine.so",
  "entryPoint": "game_main_tick"
}
```

| Campo | Tipo | Significado |
|---|---|---|
| `type` | `native` \| `wine` | Modelo de execução (padrão: `native`) |
| `name`, `version`, `author` | string | Metadados exibidos no launcher |
| `mainLibrary` | string | Caminho **relativo** da `.so` ARM64 (modelo `native`) |
| `entryPoint` | string | Símbolo de entrada padrão: `game_main_tick` |

---

## 2. Tipo `native` — motor recompilado (modelo A)

O pacote traz uma biblioteca ARM64 que **já é** o motor do jogo, com o DXVK
embutido. O app faz `dlopen` dela dentro do processo do jogo.

A biblioteca deve exportar (opcionalmente):

```c
void game_main_tick(void);                       // chamado pelo loop do app
int  pcshim_input_event(const AInputEvent* ev);  // recebe teclado/toque/gamepad
```

Sem `game_main_tick`, o motor conduz o próprio loop e o app só mantém a janela
viva, mede FPS e encaminha o input (nesse caso `pcshim_input_event` é
obrigatório para o jogo responder).

Exemplo real: o build de `codepdbh/nfsu2-recompiled` (SPEED2.EXE recompilado
de x86-32 para ARM64).

```
meu_runtime.zip
├── meta.json
└── libgame_engine.so
```

---

## 3. Tipo `wine` — tradutor + Wine + jogo (modelo B)

O pacote traz um **prefixo do Wine**, o tradutor de CPU (Box64/FEX) e o DXVK
(`d3d9.dll` de 32 bits). O app **não** carrega isso como biblioteca: ele
executa um lançador como processo filho e conversa com ele.

```json
{
  "type": "wine",
  "name": "Wine + DXVK (i686)",
  "version": "1.0.0",
  "author": "comunidade",

  "prefix": "prefix",
  "exe": "drive_c/jogo/Game.exe",
  "launcher": "bin/box64",
  "launcherArgs": ["wine64"],
  "env": {
    "DXVK_LOG_LEVEL": "info",
    "WINEDLLPATH": "prefix/drive_c/windows/system32"
  },
  "inputBridge": "bin/pcshim_input.so",
  "displayWidth": 1280,
  "displayHeight": 720
}
```

| Campo | Significado |
|---|---|
| `prefix` | Pasta do prefixo do Wine (relativa à raiz do pacote) |
| `exe` | Executável do jogo dentro do prefixo (`drive_c/...`) |
| `launcher` | Executável ARM64 que inicia o tradutor (ex.: `bin/box64`) |
| `launcherArgs` | Argumentos do lançador |
| `env` | Variáveis de ambiente extras (`KEY=VAL`) |
| `inputBridge` | `.so` opcional injetada no processo convidado |
| `displayWidth/Height` | Tamanho do framebuffer compartilhado |

A linha de comando montada pelo app é:

```
<launcher> <launcherArgs...> <caminho absoluto do exe dentro do prefixo>
```

O prefixo do Wine tem o drive C em `drive_c/`, então
`"exe": "drive_c/jogo/Game.exe"` vira `<pacote>/prefix/drive_c/jogo/Game.exe`.

O app sempre define `WINEPREFIX` e, se o pacote não definir, `WINEDLLPATH`
com `system32` + `syswow64`.

---

## 4. O contrato do guest (processo filho)

O processo convidado recebe estas variáveis de ambiente:

| Variável | Conteúdo |
|---|---|
| `PCSHIM_SOCKET` | Caminho do socket UNIX (AF_UNIX, SOCK_STREAM) |
| `PCSHIM_FB_FD` | Número do fd do framebuffer compartilhado (herdado no `exec`) |
| `PCSHIM_FB_WIDTH` / `PCSHIM_FB_HEIGHT` | Dimensões do framebuffer |
| `PCSHIM_EXE` | Caminho absoluto do executável do jogo |
| `WINEPREFIX` | Prefixo do Wine |

**Socket — do host para o guest** (registro fixo de 28 bytes, little-endian):

```
uint32 type     1=key down, 2=key up, 3=motion, 4=button, 5=focus
uint32 action   0=down/cancel, 1=up, 2=move
int32  a        keycode (1/2) ou índice do ponteiro (3)
int32  b        x em ponto fixo 16.16 (3) ou botão (4)
int32  c        y em ponto fixo 16.16 (3)
int64  ts       timestamp monotônico em ms
```

**Socket — do guest para o host** (1 byte): `R` = pronto, `F` = quadro novo
no framebuffer, `X` = vai encerrar.

**Framebuffer**: RGBA8888, `width * height * 4` bytes, mapeado com
`MAP_SHARED`. O guest escreve e avisa com `F`; o app publica na surface com
`ANativeWindow_lock`/`unlockAndPost` (escalonamento nearest-neighbour quando a
resolução interna é menor que a janela).

---

## 5. Pacote de referência (para testar sem Wine)

`tools/guest/pcshim_guest.c` implementa o contrato acima sem ser um jogo: ele
desenha um gradiente e mostra um cursor que segue o input. Serve para validar
o caminho completo num aparelho real.

```bash
./tools/guest/build.sh                 # precisa do NDK
./tools/guest/make-package.sh          # gera o .zip
# Launcher > Pacotes de runtime > importar tools/guest/build/pcshim-reference-runtime.zip
```

---

## 6. Montando um pacote de verdade (Wine + DXVK)

Receitas com pins prontos: `zenithblue-oss/panplay` (`scripts/components.env`
fixa FEX, DXVK, Box64 e áudio) e `brunodev85/winlator` (Wine para Android +
formato de "installable components"). Para D3D9 nativo no Android (sem Wine na
parte gráfica): `ggt64818-sys/vkwind`.

Layout típico do pacote:

```
runtime.zip
├── meta.json
├── bin/
│   ├── box64                 # tradutor de CPU (ARM64)
│   └── pcshim_input.so       # ponte de input (opcional)
└── prefix/
    ├── drive_c/
    │   ├── jogo/Game.exe     # o jogo (cópia do usuário)
    │   └── windows/
    │       ├── system32/d3d9.dll    # DXVK 32 bits
    │       └── syswow64/d3d9.dll
    └── system.reg
```

Pontos de atenção:

- O `.so` do tradutor e o binário do Wine precisam ser **ARM64**; o instalador
  recusa PE/x86 com mensagem explícita.
- O DXVK de 32 bits (`syswow64/d3d9.dll`) é o que o jogo x86 carrega.
- Um prefixo do Wine tem dezenas de MB: o limite atual do instalador é 500 MB
  descompactados.
