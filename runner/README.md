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

### O que ainda depende do usuário

- **Os arquivos do jogo** (o APK não pode conter jogo comercial).
- **Drivers de GPU fora do Adreno**: Mali precisa do PanVK, PowerVR precisa de
  um driver compatível. Dá para automatizar o download no primeiro uso
  (Fase 2).

## Limites conhecidos

- Jogos com anti-cheat/DRM podem não funcionar.
- O upstream é atualizado quase toda semana; o pin precisa ser acompanhado.
- A build do upstream não tem CI próprio: cada build nosso é uma aposta no
  estado do repositório pinado (por isso o log vai para o *job summary*).
