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
# Fase 1 (esta fase): assinatura de release.
# Fase 3 vai acrescentar aqui a injecao dos hooks (LD_PRELOAD) no processo
# do jogo.
set -euo pipefail

APP_ROOT="${1:?uso: apply.sh <winlator-app-dir>}"
MODULE_GRADLE="$APP_ROOT/app/build.gradle"

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

if grep -q "$MARKER" "$MODULE_GRADLE"; then
    echo "patch do runner ja aplicado"
    exit 0
fi

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
