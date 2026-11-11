package com.winlator.hubgame;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Fase 2" do runner: descoberta e importacao automatica de jogos de PC.
 *
 * <p>O que o upstream do Winlator nao faz, e que mais incomoda o usuario: nao cria
 * atalhos para os executaveis que estao no armazenamento compartilhado (a unidade
 * {@code D:} do container) e nao aplica nenhum perfil por jogo.
 *
 * <p>O jogo e copiado para dentro do container (unidade {@code C:}), que e gravavel
 * pelo app. Sem essa copia o guest nao consegue gravar save/config no lugar de
 * origem.
 */
public final class HubGameImporter {

    /** Pasta dentro do container onde os jogos importados sao gravados. */
    private static final String IMPORT_DIR = "hubgame";

    /** Extensao dos executaveis de Windows considerados jogos. */
    private static final String[] GAME_EXTENSIONS = {".exe"};

    /** Profundidade maxima da varredura, para nao varrer o armazenamento inteiro. */
    private static final int MAX_DEPTH = 3;

    private HubGameImporter() {
    }

    /**
     * Importa os jogos encontrados no armazenamento compartilhado para dentro do
     * container e cria os atalhos .desktop com o perfil de cada jogo.
     *
     * @param container  container de destino
     * @param sharedRoot pasta do armazenamento compartilhado (unidade D: do container)
     * @param onProgress callback opcional com o nome do jogo processado
     * @return quantidade de jogos importados
     */
    public static int importGames(Container container, File sharedRoot, java.util.function.Consumer<String> onProgress) {
        if (sharedRoot == null || !sharedRoot.isDirectory()) return 0;

        File importDir = new File(container.getUserDir(), IMPORT_DIR);
        int imported = 0;

        for (File exe : findExecutables(sharedRoot, 0)) {
            String exeName = exe.getName();
            if (!isGame(exeName)) continue;

            if (onProgress != null) onProgress.accept(exeName);

            // O jogo precisa da pasta de dados que fica ao lado do executavel
            // (data/, anim/, models/...). Copia a pasta inteira quando o executavel
            // esta em uma subpasta; se estiver na raiz, copia so o arquivo.
            File exeDir = exe.getParentFile();
            File targetDir = new File(importDir, exeDir.getName());
            File copiedExe;

            if (importDir.equals(exeDir)) {
                copiedExe = new File(targetDir, exeName);
                if (!FileUtils.copy(exe, copiedExe)) continue;
            }
            else {
                if (!FileUtils.copy(exeDir, targetDir)) continue;
                copiedExe = new File(targetDir, exeName);
            }

            if (!copiedExe.isFile()) continue;
            if (writeShortcut(container, copiedExe)) imported++;
        }

        return imported;
    }

    /**
     * Grava o atalho .desktop do jogo, com o perfil do jogo na secao [Extra Data].
     */
    public static boolean writeShortcut(Container container, File exe) {
        String dosPath = WineUtils.unixToDOSPath(exe.getPath(), container);
        if (dosPath.isEmpty()) return false;

        HubGameProfiles.Profile profile = HubGameProfiles.profileFor(exe.getName());
        String content = HubGameProfiles.buildDesktopEntry(exe.getPath(), dosPath, profile);

        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return false;

        File shortcut = new File(desktopDir, HubGameProfiles.nameOf(exe.getName()) + ".desktop");
        return FileUtils.writeString(shortcut, content);
    }

    /** Verdadeiro quando o nome parece o de um jogo (e nao de um instalador). */
    static boolean isGame(String fileName) {
        return HubGameProfiles.isGameExecutable(fileName);
    }

    /** Procura executaveis recursivamente, com limite de profundidade. */
    static List<File> findExecutables(File dir, int depth) {
        List<File> result = new ArrayList<>();
        if (depth > MAX_DEPTH) return result;

        File[] children = dir.listFiles();
        if (children == null) return result;

        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.isDirectory()) {
                // Nao desce em pastas de sistema do Android.
                String name = child.getName();
                if (name.startsWith(".") || name.equalsIgnoreCase("Android") || name.equalsIgnoreCase("data")) {
                    continue;
                }
                result.addAll(findExecutables(child, depth + 1));
            }
            else if (child.isFile()) {
                result.add(child);
            }
        }
        return result;
    }
}
