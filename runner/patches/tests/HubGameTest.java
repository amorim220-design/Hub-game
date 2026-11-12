package com.winlator.hubgame;

import java.util.ArrayList;
import java.util.List;

/**
 * Harness local do runner: valida o que o runnerpatch gera contra o que o upstream
 * do Winlator consegue ler.
 *
 * <p>Nao roda no aparelho: e um programa de console (javac/java) que roda na maquina
 * de build, em {@code runner/patches/tests/run.sh}. Ele reimplementa, caractere a
 * caractere, a leitura de atalho do upstream (com.winlator.container.Shortcut e
 * com.winlator.core.StringUtils.unescapeDOSPath) e confere que o caminho do jogo
 * sobrevive a ida e volta.
 */
public final class HubGameTest {

    // ---- copia literal de com.winlator.core.StringUtils (upstream) ------------

    static String escapeDOSPath(String path) {
        return path.replace("\\", "\\\\").replace(" ", "\\ ");
    }

    static String unescapeDOSPath(String path) {
        return path.replaceAll("\\\\([^\\\\]+)", "$1").replaceAll("\\\\([^\\\\]+)", "$1").replaceAll("\\\\\\\\", "\\\\").trim();
    }

    // ---- copia do parser de com.winlator.container.Shortcut (upstream) ---------

    /** Reproduz como o upstream extrai o caminho de um arquivo .desktop. */
    static String shortcutPath(String desktopFileContent) {
        String execArgs = "";
        String section = "";

        for (String line : desktopFileContent.split("\n", -1)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf("]"));
            }
            else {
                int index = line.indexOf("=");
                if (index == -1) continue;
                String key = line.substring(0, index);
                String value = line.substring(index + 1);
                if (section.equals("Desktop Entry") && key.equals("Exec")) {
                    execArgs = value;
                }
            }
        }

        if (execArgs.isEmpty()) return "";
        return unescapeDOSPath(execArgs.substring(execArgs.lastIndexOf("wine ") + 4));
    }

    /** Reproduz como o upstream monta o comando final (XServerDisplayActivity). */
    static String wineStartCommand(String shortcutPathValue) {
        if (shortcutPathValue.isEmpty()) return "";
        String execDir = dirname(shortcutPathValue);
        String filename = getName(shortcutPathValue);
        return "/dir " + escapeDOSPath(execDir) + " \"" + filename + "\"";
    }

    static String getName(String path) {
        path = removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(index + 1);
    }

    static String dirname(String path) {
        path = removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(0, index);
    }

    static String removeEndSlash(String value) {
        while (value.endsWith("/") || value.endsWith("\\")) value = value.substring(0, value.length() - 1);
        return value;
    }

    // ---- o que o runnerpatch produz ------------------------------------------

    /** Caminho unix -> caminho DOS, como WineUtils.unixToDOSPath faz para o C:. */
    static String toDosPath(String unixPath, String containerRootDir) {
        String prefix = containerRootDir + "/.wine/drive_c";
        if (!unixPath.startsWith(prefix)) return "";
        String dosPath = unixPath.substring(prefix.length()).replace("/", "\\");
        if (!dosPath.startsWith("\\")) dosPath += "\\";
        return "C:" + removeEndSlash(dosPath);
    }

    // ---- testes ----------------------------------------------------------------

    private static int failures = 0;

    static void check(String label, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("  ok   " + label);
        }
        else {
            failures++;
            System.out.println("  FAIL " + label);
            System.out.println("       esperado: [" + expected + "]");
            System.out.println("       obtido  : [" + actual + "]");
        }
    }

    static void check(String label, boolean expected, boolean actual) {
        check(label, String.valueOf(expected), String.valueOf(actual));
    }

    /**
     * Copia literal de com.winlator.renderer.ViewTransformation (upstream): e o
     * que decide o tamanho da area util na tela fisica.
     */
    static int[] scaledArea(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        float aspect = Math.min((float) outerWidth / innerWidth, (float) outerHeight / innerHeight);
        return new int[]{Math.min((int) Math.ceil(innerWidth * aspect), outerWidth),
                Math.min((int) Math.ceil(innerHeight * aspect), outerHeight)};
    }

    public static void main(String[] args) {
        String root = "/data/data/com.winlator/files/rootfs/home/xuser";
        String unixExe = root + "/.wine/drive_c/users/xuser/hubgame/GT4_S4N_4NDR34S/gta_sa.exe";
        String dosExe = toDosPath(unixExe, root);
        check("caminho DOS do container", "C:\\users\\xuser\\hubgame\\GT4_S4N_4NDR34S\\gta_sa.exe", dosExe);

        // Ida e volta pelo atalho gerado pelo patch.
        HubGameProfiles.Profile profile = HubGameProfiles.profileFor("gta_sa.exe");
        String desktop = HubGameProfiles.buildDesktopEntry(unixExe, dosExe, "GTA San Andreas", profile);
        System.out.println("\n.conteudo do .desktop gerado:\n" + desktop);

        String parsed = shortcutPath(desktop);
        check("caminho lido de volta pelo upstream", dosExe, parsed);
        check("comando final do wine", "/dir C:\\\\users\\\\xuser\\\\hubgame\\\\GT4_S4N_4NDR34S \"gta_sa.exe\"",
                wineStartCommand(parsed));

        // NFSU2: o Wine anuncia UM unico modo ao guest - o tamanho da tela do
        // X, que e o screenSize do container. O NFSU2 so aceita modos 4:3
        // (640x480, 800x600, 1024x768, 1280x960, 1280x1024), entao o
        // container tem de ser 800x600 - em 1280x720 o jogo nao acha modo e
        // nao renderiza. Com 800x600, o renderer amplia a area para
        // 1440x1080 (100% da altura da tela).
        HubGameProfiles.Profile nfsu2 = HubGameProfiles.profileFor("SPEED2.EXE");
        check("perfil do NFSU2: screenSize", "800x600", nfsu2.screenSize);
        check("perfil do NFSU2: forceFullscreen", "1", nfsu2.forceFullscreen);
        check("perfil do NFSU2: controlsProfile", "4", nfsu2.controlsProfile);

        // GTA SA: mesmo motivo - so conhece modos 4:3 e o proprio exe traz
        // "Cannot find 800x600x32 video mode".
        check("perfil do GTA: screenSize", "800x600", profile.screenSize);
        check("perfil do GTA: forceFullscreen", "1", profile.forceFullscreen);
        check("perfil do GTA: envVars", "WINEESYNC=0", profile.envVars);

        // Jogo desconhecido: nao forcar resolucao, fullscreen nem controles.
        HubGameProfiles.Profile other = HubGameProfiles.profileFor("de.exe");
        check("perfil generico: sem screenSize", "", other.screenSize == null ? "" : other.screenSize);
        check("perfil generico: sem envVars", "", other.envVars == null ? "" : other.envVars);
        check("perfil generico: sem forceFullscreen", "", other.forceFullscreen == null ? "" : other.forceFullscreen);
        check("perfil generico: sem controlsProfile", "", other.controlsProfile == null ? "" : other.controlsProfile);

        // O atalho de um jogo generico nao deve ter [Extra Data] sem nada util.
        String genericDesktop = HubGameProfiles.buildDesktopEntry(unixExe, dosExe, "Jogo",
                HubGameProfiles.profileFor("de.exe"));
        check("atalho generico sem [Extra Data]", false, genericDesktop.contains("[Extra Data]"));

        // Um jogo com espaco no nome.
        String spaced = toDosPath(root + "/.wine/drive_c/users/xuser/hubgame/My Game/run.exe", root);
        String spacedDesktop = HubGameProfiles.buildDesktopEntry(
                root + "/.wine/drive_c/users/xuser/hubgame/My Game/run.exe", spaced, "My Game",
                HubGameProfiles.profileFor("run.exe"));
        check("caminho com espaco sobrevive", spaced, shortcutPath(spacedDesktop));

        // O nome mostrado e o do jogo, nao o do executavel.
        check("nome do atalho", "GTA San Andreas",
                desktop.split("\n")[2].substring("Name=".length()));

        // Exec precisa conter "wine " para o upstream achar o caminho.
        check("Exec contem 'wine '", true, desktop.contains("Exec=wine "));

        // O perfil tem que ir na secao [Extra Data], que e o que o upstream le.
        check("secao [Extra Data] presente", true, desktop.contains("[Extra Data]"));
        check("screenSize no Extra Data", true, desktop.contains("screenSize=800x600"));
        check("forceFullscreen no Extra Data", true, desktop.contains("forceFullscreen=1"));
        check("controlsProfile no Extra Data", true, desktop.contains("controlsProfile=4"));
        check("envVars no Extra Data", true, desktop.contains("envVars=WINEESYNC=0"));

        // O importer nao deve trazer instaladores.
        check("setup.exe nao e jogo", false, HubGameProfiles.isGameExecutable("setup.exe"));
        check("gta_sa.exe e jogo", true, HubGameProfiles.isGameExecutable("gta_sa.exe"));
        check("GTA_SA.EXE (maiusculo) e jogo", true, HubGameProfiles.isGameExecutable("GTA_SA.EXE"));
        check("dxsetup.exe nao e jogo", false, HubGameProfiles.isGameExecutable("dxsetup.exe"));

        // A tela do container e o unico modo que o Wine anuncia ao guest. Com o
        // container em 800x600 (modo que o NFSU2 aceita), o renderer do app
        // amplia a area util para 1440x1080 - 100% da altura da tela de
        // 2400x1080. Com 1280x720 (modo que o NFSU2 nao aceita), o jogo nao
        // renderiza.
        int[] area = scaledArea(2400, 1080, 800, 600);
        check("area util em 800x600 (largura)", "1440", String.valueOf(area[0]));
        check("area util em 800x600 (altura = tela toda)", "1080", String.valueOf(area[1]));

        int[] area720 = scaledArea(2400, 1080, 1280, 720);
        check("area util em 1280x720 (altura = tela toda)", "1080", String.valueOf(area720[1]));

        System.out.println();
        if (failures == 0) {
            System.out.println("todos os testes passaram");
        }
        else {
            System.out.println(failures + " teste(s) falharam");
            System.exit(1);
        }
    }
}
