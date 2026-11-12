#!/usr/bin/env bash
# Aplica os patches do runner sobre o app do upstream (Winlator).
#
#   sh runner/patches/apply.sh <winlator-app-dir>
#
# <winlator-app-dir> e o diretorio do submodule 'app' (o repositorio
# winlator-app). O modulo Android esta em <dir>/app/build.gradle; o
# <dir>/build.gradle e o projeto raiz e nao aceita um bloco android{}.
#
# Os patches sao aplicados por insercao (nao por diff), para nao quebrar a
# cada atualizacao do upstream. E idempotente.
#
# Fase 1: assinatura de release (o APK precisa ser instalavel pelo instalador
#          normal do Android).
# Fase 2: importacao automatica de jogos de PC + perfis por jogo.
#          - copia as classes em com.winlator.hubgame para o upstream
#          - insere a chamada de importacao no ContainerManager do upstream
set -euo pipefail

APP_ROOT="${1:?uso: apply.sh <winlator-app-dir>}"
MODULE_GRADLE="$APP_ROOT/app/build.gradle"
PATCHES_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -f "$MODULE_GRADLE" ]; then
    #compat: se a pessoa passou o diretorio do modulo, aceita assim mesmo
    if [ -f "$APP_ROOT/build.gradle" ]; then
        MODULE_GRADLE="$APP_ROOT/build.gradle"
    else
        echo "build.gradle do modulo nao encontrado em $APP_ROOT/app/build.gradle" >&2
        exit 1
    fi
fi

MARKER="# --- hub-game runner patch ---"

if ! grep -q "$MARKER" "$MODULE_GRADLE"; then

cat >> "$MODULE_GRADLE" <<'EOF'

// --- hub-game runner patch ---
// O upstream nao define signingConfig para release: sem isso o assembleRelease
// gera um APK sem assinatura. Aqui usamos o keystore do runner, indicado por
// KEYSTORE_PATH (absoluto) ou, se nao houver, o do proprio runner.
android {
    signingConfigs {
        create("runnerRelease") {
            def ksPath = System.getenv("KEYSTORE_PATH")
            def ksFile = ksPath != null ? file(ksPath) : file("${rootDir}/../../../keystore/runner.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("STORE_PASSWORD") ?: "runner"
                keyAlias = System.getenv("KEY_ALIAS") ?: "runner"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "runner"
            }
        }
    }
    buildTypes {
        release {
            if (signingConfigs.getByName("runnerRelease").storeFile != null) {
                signingConfig = signingConfigs.getByName("runnerRelease")
            }
        }
    }
}
EOF

echo "patch de assinatura aplicado em $MODULE_GRADLE"

else
    echo "patch de assinatura ja aplicado em $MODULE_GRADLE"
fi

# --- android.injected.testOnly ----------------------------------------------
# O AGP marca a variante como "de teste" e injeta android:testOnly="true" no
# manifest. O instalador normal do Android recusa APK de teste
# (INSTALL_FAILED_TEST_ONLY -> "o pacote parece ser invalido"), so o build
# precisa desligar isso.
PROPS="$APP_ROOT/gradle.properties"
if [ -f "$PROPS" ]; then
    if ! grep -q "android.injected.testOnly" "$PROPS"; then
        printf '\n# hub-game runner patch: o APK precisa ser instalavel pelo instalador normal\nandroid.injected.testOnly=false\n' >> "$PROPS"
        echo "android.injected.testOnly=false aplicado em gradle.properties"
    else
        echo "android.injected.testOnly ja definido em gradle.properties"
    fi
fi

# --- Fase 2: importacao automatica de jogos + perfis por jogo -----------------
# 1) copia as classes de com.winlator.hubgame para o upstream
# 2) insere a chamada de importacao no ContainerManager (createContainer)
#
# Por que insercao e nao diff: o upstream do Winlator muda toda semana e um
# diff quebraria o build a cada atualizacao. As ancoras abaixo sao estaveis
# ha anos no upstream e o patch falha alto (exit 1) se nao forem encontradas,
# em vez de compilar silenciosamente sem o recurso.
HUBGAME_PKG="com/winlator/hubgame"
HUBGAME_JAVA_DIR="$APP_ROOT/app/src/main/java"
HUBGAME_SRC="$HUBGAME_JAVA_DIR/$HUBGAME_PKG"

if [ ! -d "$HUBGAME_JAVA_DIR" ]; then
    echo "upstream sem app/src/main/java: a estrutura mudou?" >&2
    exit 1
fi

mkdir -p "$HUBGAME_SRC"
cp "$PATCHES_DIR"/src/$HUBGAME_PKG/*.java "$HUBGAME_SRC"/
echo "classes hubgame copiadas para $HUBGAME_SRC"

# Insere a chamada de importacao em ContainerManager.createContainer.
CONTAINER_MANAGER="$APP_ROOT/app/src/main/java/com/winlator/container/ContainerManager.java"
if [ ! -f "$CONTAINER_MANAGER" ]; then
    echo "ContainerManager.java nao encontrado em $CONTAINER_MANAGER" >&2
    exit 1
fi

CALL_MARKER="HubGameBootstrap.onContainerCreated(context, container);"
IMPORT_MARKER="import com.winlator.hubgame.HubGameBootstrap;"

if grep -q "$CALL_MARKER" "$CONTAINER_MANAGER"; then
    echo "patch de importacao de jogos ja aplicado"
else
    # Ancora: a linha que adiciona o container recem-criado a lista. E unica no
    # arquivo e o ponto onde o container ja esta pronto para ser usado.
    #
    # awk (e nao python) porque o runner de CI e ubuntu, mas o build local roda
    # em qualquer shell; awk esta em todo lugar. O script falha alto (exit 1)
    # se a ancora nao aparecer exatamente uma vez, em vez de compilar
    # silenciosamente sem o recurso.
    TMP_FILE="$(mktemp)"
    awk -v marker="$CALL_MARKER" -v indent="            " '
        {
            print
            if ($0 == "            containers.add(container);") {
                count++
                if (count > 1) {
                    print "ancora duplicada" > "/dev/stderr"
                    exit 3
                }
                print indent marker
            }
        }
        END { if (count != 1) exit 4 }
    ' "$CONTAINER_MANAGER" > "$TMP_FILE" || {
        rc=$?
        rm -f "$TMP_FILE"
        echo "ancora 'containers.add(container);' nao encontrada exatamente uma vez em $CONTAINER_MANAGER - o upstream mudou?" >&2
        exit 1
    }

    mv "$TMP_FILE" "$CONTAINER_MANAGER"
    echo "patch de importacao de jogos aplicado em $CONTAINER_MANAGER"
fi


# --- Fase 2 (parte 2): sincronizar os atalhos ao abrir a aba de atalhos -----
# A importacao roda na criacao do container. Para que uma correcao de perfil
# (resolucao, forcar fullscreen) chegue aos atalhos que JA existem, a
# sincronizacao tambem roda quando a aba de atalhos e aberta.
SHORTCUTS_FRAGMENT="$APP_ROOT/app/src/main/java/com/winlator/ShortcutsFragment.java"
if [ ! -f "$SHORTCUTS_FRAGMENT" ]; then
    echo "ShortcutsFragment.java nao encontrado em $SHORTCUTS_FRAGMENT" >&2
    exit 1
fi

SYNC_MARKER="com.winlator.hubgame.HubGameBootstrap.syncContainer("


if grep -q "$SYNC_MARKER" "$SHORTCUTS_FRAGMENT"; then
    echo "patch de sincronizacao de atalhos ja aplicado"
else
    # Ancora: a linha que carrega a lista de atalhos. E unica no arquivo e
    # e o ponto onde o container ja esta carregado.
    SYNC_ANCHOR="        ArrayList<Shortcut> shortcuts = manager.loadShortcuts(selectedFolder);"
    if ! grep -qF "$SYNC_ANCHOR" "$SHORTCUTS_FRAGMENT"; then
        echo "ancora de sincronizacao nao encontrada em $SHORTCUTS_FRAGMENT - o upstream mudou?" >&2
        exit 1
    fi

    TMP_FILE="$(mktemp)"
    awk -v anchor="$SYNC_ANCHOR" -v call="        for (com.winlator.container.Container c : manager.getContainers()) com.winlator.hubgame.HubGameBootstrap.syncContainer(getContext(), c, \"atalhos\");" '
        { print }
        $0 == anchor { print call }
    ' "$SHORTCUTS_FRAGMENT" > "$TMP_FILE"

    mv "$TMP_FILE" "$SHORTCUTS_FRAGMENT"
    echo "patch de sincronizacao de atalhos aplicado em $SHORTCUTS_FRAGMENT"
fi


# --- Fase 2 (parte 3): menu de resolucao dentro do jogo ----------------------
# O jogador nao deve precisar editar o container para trocar a resolucao. O
# patch adiciona ao menu lateral do jogo um item "Resolução" que abre um
# dialogo, grava screenSize no atalho e reinicia a sessao.
#
# 1) recursos: layout do dialogo, strings e o item de menu
HUB_RES_DIR="$APP_ROOT/app/src/main/res"
if [ ! -d "$HUB_RES_DIR" ]; then
    echo "upstream sem app/src/main/res: a estrutura mudou?" >&2
    exit 1
fi

cp "$PATCHES_DIR"/res/layout/hub_game_resolution_dialog.xml "$HUB_RES_DIR/layout/"
cp "$PATCHES_DIR"/res/values/hub_game_strings.xml "$HUB_RES_DIR/values/"
cp "$PATCHES_DIR"/res/menu/xserver_menu.xml "$HUB_RES_DIR/menu/"
echo "recursos hub-game copiados para $HUB_RES_DIR"

# 2) accessor do atalho + item do menu no XServerDisplayActivity
XSD="$APP_ROOT/app/src/main/java/com/winlator/XServerDisplayActivity.java"
if [ ! -f "$XSD" ]; then
    echo "XServerDisplayActivity.java nao encontrado em $XSD" >&2
    exit 1
fi

GET_SHORTCUT_MARKER="public Shortcut getShortcut() {"
MENU_CASE_MARKER="case R.id.menu_item_hub_game_resolution:"

if grep -q "$GET_SHORTCUT_MARKER" "$XSD" && grep -q "$MENU_CASE_MARKER" "$XSD"; then
    echo "patch do menu de resolucao ja aplicado"
else
    # Accessor do atalho: inserido logo depois do metodo getContainer().
    if ! grep -q "$GET_SHORTCUT_MARKER" "$XSD"; then
        TMP_FILE="$(mktemp)"
        awk -v getter="    public Shortcut getShortcut() {" -v getterBody="        return shortcut;" '
            { print }
            $0 == "    public Container getContainer() {" { inGetter = 1 }
            inGetter && $0 == "    }" {
                print ""
                print getter
                print getterBody
                print "    }"
                inGetter = 0
            }
        ' "$XSD" > "$TMP_FILE"
        mv "$TMP_FILE" "$XSD"
    fi

    # Item do menu: inserido logo depois do case de toggle_fullscreen.
    if ! grep -q "$MENU_CASE_MARKER" "$XSD"; then
        TMP_FILE="$(mktemp)"
        awk -v block="            case R.id.menu_item_hub_game_resolution:
                com.winlator.hubgame.HubGameResolutionDialog.show(this, shortcut);
                drawerLayout.closeDrawers();
                break;" '
            { print }
            $0 == "            case R.id.menu_item_toggle_fullscreen:" { inCase = 1 }
            inCase && $0 == "                break;" {
                print block
                inCase = 0
            }
        ' "$XSD" > "$TMP_FILE"
        mv "$TMP_FILE" "$XSD"
    fi

    echo "patch do menu de resolucao aplicado em $XSD"
fi

# O import correspondente: sem ele o Java nao compila. Vai para o bloco de
# imports do upstream, em ordem alfabetica, logo antes do import do RootFS.
if ! grep -q "$IMPORT_MARKER" "$CONTAINER_MANAGER"; then
    # Ancora: o import de com.winlator.xenvironment.RootFS. O ContainerManager
    # esta no mesmo pacote do Container (por isso nao ha import dele), entao
    # RootFS e a referencia estavel do bloco de imports do upstream.
    IMPORT_ANCHOR="import com.winlator.xenvironment.RootFS;"
    if ! grep -q "^$IMPORT_ANCHOR$" "$CONTAINER_MANAGER"; then
        echo "ancora de import nao encontrada em $CONTAINER_MANAGER - o upstream mudou?" >&2
        exit 1
    fi

    TMP_FILE="$(mktemp)"
    awk -v anchor="$IMPORT_ANCHOR" -v imp="$IMPORT_MARKER" '
        { print }
        $0 == anchor { count++; print imp }
        END { if (count != 1) exit 4 }
    ' "$CONTAINER_MANAGER" > "$TMP_FILE" || {
        rm -f "$TMP_FILE"
        echo "ancora de import duplicada ou ausente em $CONTAINER_MANAGER - o upstream mudou?" >&2
        exit 1
    }

    mv "$TMP_FILE" "$CONTAINER_MANAGER"
    echo "import de HubGameBootstrap adicionado em $CONTAINER_MANAGER"
fi

