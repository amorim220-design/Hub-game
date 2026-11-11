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
            String displayName = gameName(exeDir, exeName, sharedRoot);

            if (sameFile(exeDir, sharedRoot)) {
                // O .exe esta direto na pasta compartilhada: copiar a pasta
                // inteira arrastaria tudo que esta em Download (gigabytes).
                copiedExe = new File(targetDir, exeName);
                if (!copiedExe.isFile() && !FileUtils.copy(exe, copiedExe)) continue;
            }
            else {
                // Espelha a pasta do jogo: copia o que falta, sobrescreve o que
                // mudou e remove o que sumiu na origem. Sem isso, um mod
                // desativado na origem (ex.: renomeado para .bak) continuaria
                // no container, e um .dll de mod quebrado impede o jogo de
                // carregar. A copia so toca no que difere, entao nao reescreve
                // gigabytes a cada abertura do app.
                if (!mirrorDirectory(exeDir, targetDir)) continue;
                copiedExe = new File(targetDir, exeName);
            }

            if (!copiedExe.isFile()) continue;
            if (writeShortcut(container, copiedExe, displayName)) imported++;
        }

        return imported;
    }

    /**
     * Nome do jogo como aparece no launcher: o nome da pasta do jogo quando o
     * executavel esta em uma subpasta (o caso comum), senao o nome do arquivo.
     */
    static String gameName(File exeDir, String exeName, File sharedRoot) {
        String base = HubGameProfiles.nameOf(exeName);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        if (exeDir == null) return base;

        // O executavel esta na raiz da pasta compartilhada: o nome do arquivo e o
        // melhor que temos (a pasta se chama "Download", que nao identifica nada).
        if (sharedRoot != null && sameFile(exeDir, sharedRoot)) return base;

        String dirName = exeDir.getName();
        if (dirName.isEmpty()) return base;
        return dirName;
    }

    /**
     * Grava o atalho .desktop do jogo, com o perfil do jogo na secao [Extra Data].
     *
     * <p>Sempre reescreve: assim uma mudanca no perfil (por exemplo, corrigir
     * a resolucao de um jogo que saia sozinho) vale no proximo start do app,
     * sem o usuario precisar apagar o atalho na mao.
     */
    public static boolean writeShortcut(Container container, File exe, String displayName) {
        String dosPath = WineUtils.unixToDOSPath(exe.getPath(), container);
        if (dosPath.isEmpty()) return false;

        HubGameProfiles.Profile profile = HubGameProfiles.profileFor(exe.getName());
        String content = HubGameProfiles.buildDesktopEntry(exe.getPath(), dosPath, displayName, profile);

        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return false;

        // Remove o atalho antigo, que pode ter outro nome (versao anterior do
        // patch nomeava o atalho pelo executavel).
        String exeBase = HubGameProfiles.nameOf(exe.getName());
        if (!exeBase.equals(displayName)) {
            File old = new File(desktopDir, exeBase + ".desktop");
            if (old.isFile()) old.delete();
        }

        File shortcut = new File(desktopDir, displayName + ".desktop");
        return FileUtils.writeString(shortcut, content);
    }

    /** Verdadeiro quando o nome parece o de um jogo (e nao de um instalador). */
    static boolean isGame(String fileName) {
        return HubGameProfiles.isGameExecutable(fileName);
    }

    /**
     * Compara dois caminhos sem depender do canonical path (o destino nem existe ainda).
     */
    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    /**
     * Espelha {@code src} em {@code dst}: copia o que falta, sobrescreve o que
     * mudou e remove o que nao existe mais na origem.
     *
     * <p>Comparacao por nome + tamanho + data de modificacao: assim um jogo de
     * gigabytes so reescreve o que mudou de fato.
     */
    static boolean mirrorDirectory(File src, File dst) {
        if (!src.isDirectory()) return false;
        if (!dst.isDirectory() && !dst.mkdirs()) return false;

        File[] srcFiles = src.listFiles();
        if (srcFiles == null) return false;

        for (File srcFile : srcFiles) {
            File dstFile = new File(dst, srcFile.getName());
            if (srcFile.isDirectory()) {
                if (!mirrorDirectory(srcFile, dstFile)) return false;
            }
            else if (!sameContent(srcFile, dstFile)) {
                if (!FileUtils.copy(srcFile, dstFile)) return false;
            }
        }

        // Remove o que ficou para tras na origem (mod desativado, por exemplo).
        File[] dstFiles = dst.listFiles();
        if (dstFiles != null) {
            for (File dstFile : dstFiles) {
                if (!new File(src, dstFile.getName()).exists()) {
                    FileUtils.delete(dstFile);
                }
            }
        }

        return true;
    }

    /** Verdadeiro quando os dois arquivos tem o mesmo conteudo (mesmo tamanho e mesma data). */
    private static boolean sameContent(File a, File b) {
        if (!b.isFile()) return false;
        if (a.length() != b.length()) return false;
        return a.lastModified() == b.lastModified();
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
