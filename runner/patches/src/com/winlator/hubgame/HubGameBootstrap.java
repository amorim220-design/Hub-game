package com.winlator.hubgame;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.AppUtils;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ponto de entrada da "Fase 2" do runner.
 *
 * <p>O upstream do Winlator nao cria atalhos para os executaveis que ficam no
 * armazenamento compartilhado (a unidade {@code D:} do container). Esta classe
 * faz a varredura, importa o jogo para dentro do container (unidade {@code C:},
 * gravavel) e grava o atalho {@code .desktop} com o perfil do jogo.
 *
 * <p>Chamada a partir do upstream: {@code HubGameBootstrap.onContainerCreated(context, container)}
 * logo apos o container ser criado. Como o upstream muda toda semana, o patch e
 * feito por insercao (ver {@code runner/patches/apply.sh}) e estas classes sao a
 * unica coisa que o patch precisa adicionar.
 */
public final class HubGameBootstrap {

    private static final String TAG = "HubGame";

    private static ExecutorService executor;

    private HubGameBootstrap() {
    }

    /**
     * Importa os jogos do armazenamento compartilhado para o container recem-criado.
     *
     * <p>Rodar em background e importante: a copia de um jogo pode levar
     * segundos e nao pode travar a criacao do container.
     */
    public static void onContainerCreated(final Context context, final Container container) {
        syncContainer(context, container, "container criado");
    }

    /**
     * Sincroniza os atalhos de um container.
     *
     * <p>Chamado na criacao do container e tambem a cada abertura do app: e o
     * que faz uma correcao de perfil (resolucao, forcar fullscreen) valer
     * para os atalhos que ja existem, sem o usuario precisar recriar o
     * container ou apagar o atalho na mao.
     */
    public static void syncContainer(final Context context, final Container container, final String motivo) {
        if (context == null || container == null) return;

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        executor.execute(() -> {
            try {
                File sharedRoot = sharedGamesRoot();
                log("sincronizando (" + motivo + ") container " + container.id + " de " + sharedRoot);
                int n = HubGameImporter.importGames(container, sharedRoot, name -> {
                    Log.i(TAG, "importando " + name);
                    log("  jogo: " + name);
                });
                log("atalhos processados: " + n);
                log("perfil SPEED2: " + describe(HubGameProfiles.profileFor("SPEED2.EXE")));
            }
            catch (Exception e) {
                Log.w(TAG, "falha ao importar jogos", e);
                log("ERRO: " + e);
            }
        });
    }

    /** Resumo do perfil, para diagnostico. */
    private static String describe(HubGameProfiles.Profile profile) {
        return "screenSize=" + profile.screenSize + " forceFullscreen=" + profile.forceFullscreen
                + " envVars=" + profile.envVars;
    }

    /**
     * Grava uma linha de diagnostico em /storage/emulated/0/Documents/HubGame/diag.txt.
     *
     * <p>O log do app (Documents/Winlator/logs.txt) so e escrito quando o
     * usuario exporta a tela de log. Este arquivo e escrito sozinho, para o
     * diagnostico de importacao ficar disponivel.
     */
    static void log(String message) {
        Log.i(TAG, message);
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "HubGame");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(new File(dir, "diag.txt"), true));
            writer.println(message);
            writer.close();
        }
        catch (Exception e) {
            // log e best-effort: nao pode derrubar a importacao
        }
    }

    /**
     * Pasta do armazenamento compartilhado com os jogos.
     *
     * <p>Usa {@code AppUtils.DIRECTORY_DOWNLOADS} do upstream, que e exatamente
     * a pasta que o container monta como unidade D: (ver Container.DEFAULT_DRIVES).
     */
    static File sharedGamesRoot() {
        String downloads = AppUtils.DIRECTORY_DOWNLOADS;
        if (downloads == null || downloads.isEmpty()) return null;
        return new File(downloads);
    }
}
