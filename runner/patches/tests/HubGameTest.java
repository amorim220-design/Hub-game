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

    public static void main(String[] args) {
        String root = "/data/data/com.winlator/files/rootfs/home/xuser";
        String unixExe = root + "/.wine/drive_c/users/xuser/hubgame/GT4_S4N_4NDR34S/gta_sa.exe";
        String dosExe = toDosPath(unixExe, root);
        check("caminho DOS do container", "C:\\users\\xuser\\hubgame\\GT4_S4N_4NDR34S\\gta_sa.exe", dosExe);

        // Ida e volta pelo atalho gerado pelo patch.
        HubGameProfiles.Profile profile = HubGameProfiles.profileFor("gta_sa.exe");
        String desktop = HubGameProfiles.buildDesktopEntry(unixExe, dosExe, profile);
        System.out.println("\n.conteudo do .desktop gerado:\n" + desktop);

        String parsed = shortcutPath(desktop);
        check("caminho lido de volta pelo upstream", dosExe, parsed);
        check("comando final do wine", "/dir C:\\\\users\\\\xuser\\\\hubgame\\\\GT4_S4N_4NDR34S \"gta_sa.exe\"",
                wineStartCommand(parsed));

        // Perfil do GTA: 800x600, para o modo 800x600x32 existir e o jogo ficar fullscreen.
        check("perfil do GTA: screenSize", "800x600", profile.screenSize);
        check("perfil do GTA: envVars", "WINEESYNC=0", profile.envVars);

        // Qualquer outro jogo fica em 16:9.
        HubGameProfiles.Profile other = HubGameProfiles.profileFor("de.exe");
        check("perfil generico: screenSize", "1280x720", other.screenSize);
        check("perfil generico: sem envVars", "", other.envVars == null ? "" : other.envVars);

        // Um jogo com espaco no nome.
        String spaced = toDosPath(root + "/.wine/drive_c/users/xuser/hubgame/My Game/run.exe", root);
        String spacedDesktop = HubGameProfiles.buildDesktopEntry(
                root + "/.wine/drive_c/users/xuser/hubgame/My Game/run.exe", spaced,
                HubGameProfiles.profileFor("run.exe"));
        check("caminho com espaco sobrevive", spaced, shortcutPath(spacedDesktop));

        // Exec precisa conter "wine " para o upstream achar o caminho.
        check("Exec contem 'wine '", true, desktop.contains("Exec=wine "));

        // O perfil tem que ir na secao [Extra Data], que e o que o upstream le.
        check("secao [Extra Data] presente", true, desktop.contains("[Extra Data]"));
        check("screenSize no Extra Data", true, desktop.contains("screenSize=800x600"));
        check("envVars no Extra Data", true, desktop.contains("envVars=WINEESYNC=0"));

        // O importer nao deve trazer instaladores.
        check("setup.exe nao e jogo", false, HubGameProfiles.isGameExecutable("setup.exe"));
        check("gta_sa.exe e jogo", true, HubGameProfiles.isGameExecutable("gta_sa.exe"));
        check("GTA_SA.EXE (maiusculo) e jogo", true, HubGameProfiles.isGameExecutable("GTA_SA.EXE"));
        check("dxsetup.exe nao e jogo", false, HubGameProfiles.isGameExecutable("dxsetup.exe"));

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
