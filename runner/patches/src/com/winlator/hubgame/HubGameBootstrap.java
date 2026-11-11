package com.winlator.hubgame;

import android.content.Context;
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
        if (context == null || container == null) return;

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        executor.execute(() -> {
            try {
                File sharedRoot = sharedGamesRoot();
                HubGameImporter.importGames(container, sharedRoot, name -> Log.i(TAG, "importando " + name));
            }
            catch (Exception e) {
                Log.w(TAG, "falha ao importar jogos", e);
            }
        });
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
