#!/usr/bin/env bash
# Aplica os patches do runner sobre o app do upstream (Winlator).
#
#   sh runner/patches/apply.sh <app-dir>
#
# Os patches são aplicados por inserção (não por diff), para não quebrar a
# cada atualização do upstream. É idempotente: rodar de novo não faz nada.
#
# Fase 1 (esta fase): apenas assinatura de release e filtro de ABI.
# Fase 3 vai acrescentar aqui a injeção dos hooks (LD_PRELOAD) no processo
# do jogo.
set -euo pipefail

APP_DIR="${1:?uso: apply.sh <app-dir>}"
GRADLE="$APP_DIR/build.gradle"
[ -f "$GRADLE" ] || { echo "gradle do app nao encontrado: $GRADLE" >&2; exit 1; }

MARKER="# --- hub-game runner patch ---"

if grep -q "$MARKER" "$GRADLE"; then
    echo "patch do runner ja aplicado"
    exit 0
fi

cat >> "$GRADLE" <<'EOF'

// --- hub-game runner patch ---
// O upstream nao define signingConfig para release: sem isso o assembleRelease
// gera um APK sem assinatura. Aqui usamos o keystore do runner (ou as variaveis
// de ambiente KEYSTORE_PATH/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD).
android {
    signingConfigs {
        create("runnerRelease") {
            def ksPath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/../../keystore/runner.keystore"
            def ksFile = file(ksPath)
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

echo "patch de assinatura aplicado"
