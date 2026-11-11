# Runner — APK que roda jogos de PC no Android

Este diretório constrói o **runner**: um APK único que roda jogos de PC
(x86/x86_64) em Android ARM64, baseado no [Winlator](https://github.com/brunodev85/winlator)
(LGPL-2.1), com os nossos patches por cima.

O que o upstream já resolve (e por isso não é reimplementado aqui):

| Peça | Origem |
|---|---|
| Wine para Android ARM64 | `winlator-app` (submódulo), `assets/rootfs.tzst` |
| Box64 (x86_64 → ARM64) | `installable_components/box64` |
| DXVK (D3D9/10/11 → Vulkan) | `installable_components/dxvk` |
| vkd3d (D3D12) | `installable_components/vkd3d` |
| wined3d (fallback software) | `installable_components/wined3d` |
| Turnip (Vulkan para Adreno) | `installable_components/turnip` |
| Servidor de display (vortek/gladio) | submódulos `vortek`, `gladio` |
| Prefixos Wine e componentes | app do upstream |

O que os nossos patches acrescentam (fases seguintes):

- **Fase 1 (aqui)**: assinatura de release e filtro de ABI.
- **Fase 2**: descoberta automática de jogos e instalação silenciosa dos
  componentes (DXVK/driver) no primeiro uso.
- **Fase 3**: injeção dos nossos hooks (`libfile_redirect_hook.so`,
  `libmain_hook.so`) no processo do jogo, para redirecionar caminhos `C:\...`
  e trocar o driver Vulkan por jogo.

## Build

### No CI (recomendado)

Abra **Actions → Build runner APK (Winlator base) → Run workflow**. O APK
assinado sai como artefato `hubgame-runner-arm64`.

### Local

```bash
# requisitos: JDK 17, Android SDK (platform 35, build-tools 35.0.0,
# cmake 3.22.1, ndk 24.0.8215888), git
export ANDROID_HOME=$HOME/Android/Sdk
./runner/build.sh
# saída: runner/dist/hubgame-runner-arm64.apk
```

O script clona o upstream pinado em `runner/versions.env`
(`WINLATOR_REF`), inicializa os submódulos, aplica `runner/patches/apply.sh`
e compila com `./gradlew assembleRelease` (só `arm64-v8a`).

### Versão do upstream

Edite `WINLATOR_REF` em `runner/versions.env`. Como os submódulos são pinados
pelo próprio repositório upstream, trocar o commit troca tudo junto.

## Instalar e usar

1. Instale o APK (o do upstream e o nosso têm o mesmo package id
   `com.winlator` — **desinstale o anterior** antes, ou os dados do prefixo
   ficam órfãos).
2. Na primeira execução, escolha o diretório dos jogos (o app cria o prefixo
   Wine e baixa os componentes necessários).
3. Crie um atalho para um `.exe` e rode.

Com o patch do runner (Fase 2), o passo 3 é automático: ao criar o container,
o runner varre `/storage/emulated/0/Download` (a unidade `D:` do container),
importa cada jogo para dentro do container (unidade `C:`, gravável) e grava o
atalho `.desktop` com o perfil do jogo.

### Perfis por jogo (Fase 3)

O perfil de cada jogo vai no próprio atalho, na seção `[Extra Data]` — que é o
mecanismo que o upstream do Winlator já lê (`Shortcut` +
`XServerDisplayActivity`). As opções são as mesmas que o upstream aceita:
`screenSize`, `graphicsDriver`, `dxwrapper`, `wincomponents`, `envVars`.

Regras em `runner/patches/src/com/winlator/hubgame/HubGameProfiles.java`:

| Jogo | Perfil | Motivo |
|---|---|---|
| `gta_sa.exe` | `screenSize=800x600`, `envVars=WINEESYNC=0` | O GTA SA original só conhece modos 4:3. Em 1280x720 o pedido do modo `800x600x32` falha (o próprio exe traz a string `Cannot find 800x600x32 video mode`) e o jogo cai numa janela 800x600 no canto da tela. |
| demais `.exe` | `screenSize=1280x720` | 16:9, padrão do container. |

### Por que não há LD_PRELOAD no runner

O plano original da Fase 3 era injetar `libmain_hook.so` /
`libfile_redirect_hook.so` no processo do jogo (hooks de `android_dlopen_ext`,
`fopen`, `gsl_memory_alloc_pure`). Isso não se aplica ao runner, e a verificação
mostrou por quê:

- O runner **não** roda o motor do jogo no processo do app. O app é o host; o
  jogo é um PE do Windows dentro do Wine, em processo separado. Um `.so`
  ARM64 com hook de `fopen` do bionic não alcança as chamadas do guest.
- O Wine **já** resolve o mapeamento `C:\` internamente
  (`~/.wine/dosdevices/`), então o hook de redirecionar `C:\...` seria
  redundante.
- O `BOX64_LD_PRELOAD` existe no binário do Box64, mas só carrega bibliotecas
  **x86**, e um hook ARM64 não é carregável no guest de qualquer jeito.

O que dá para fazer por jogo no runner é o que o upstream já expõe: o
`[Extra Data]` do atalho. É o mecanismo usado acima. Os hooks continuam
valendo para o outro produto do repositório (`app/`, o shell nativo), onde o
motor do jogo **é** carregado no processo do app por `dlopen`.

### O que ainda depende do usuário

- **Os arquivos do jogo** (o APK não pode conter jogo comercial).
- **Drivers de GPU fora do Adreno**: neste aparelho o driver padrão do upstream
  já é Vortek+Gladio (`GraphicsDrivers.getDefaultDriver` só escolhe Turnip
  quando detecta Adreno), então Mali funciona com o que vem embutido. Um driver
  Vulkan nativo para Mali continua sendo trabalho futuro.
- **Jogos que precisam de BIOS/`:arch`**: PS2 precisa de BIOS de PS2 (o
  `DuckStation` instalado no aparelho usa os de PS1 que estão em
  `/storage/emulated/0/games/bios`).

## Limites conhecidos

- Jogos com anti-cheat/DRM podem não funcionar.
- O upstream é atualizado quase toda semana; o pin precisa ser acompanhado.
- A build do upstream não tem CI próprio: cada build nosso é uma aposta no
  estado do repositório pinado (por isso o log vai para o *job summary*).
- O patch do runner insere código no `ContainerManager` do upstream por âncora.
  Se o upstream mudar essa âncora, o build **falha** (em vez de compilar sem o
  recurso) — é proposital; veja `runner/patches/apply.sh`.

## Assinatura

A chave de assinatura do runner fica versionada em `runner/keystore/runner.keystore`
(senha `runner`). Ela precisa ser **estável**: o Android só aceita um APK
atualizando outro quando a assinatura é a mesma. Com uma chave nova a cada
build, o aparelho recusaria a atualização
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) e o usuário teria que desinstalar — o
que apaga o container e os jogos importados.

O `build.sh` só gera a chave se ela ainda não existir, e o CI usa a mesma
versão do repositório.

## Testes do patch

```bash
sh runner/patches/tests/run.sh
```

Roda na máquina de build, sem Android SDK: reimplementa o parser de atalho do
upstream (`Shortcut` + `StringUtils.unescapeDOSPath`) e confere que o
`.desktop` gerado pelo patch volta a ser lido como o caminho certo.
