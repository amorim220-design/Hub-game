package com.winlator.hubgame;

import java.util.Locale;

/**
 * Perfis por jogo do runner ("Fase 3" do runner).
 *
 * <p>Em vez de injetar hooks no processo do jogo (que nao serve no Winlator: o Wine
 * ja mapeia o C: internamente e um LD_PRELOAD de ARM64 nao alcança o guest x86),
 * o runner grava as preferencias de cada jogo no proprio atalho .desktop, na secao
 * {@code [Extra Data]}. E exatamente o mecanismo que o upstream do Winlator ja le
 * em {@code Shortcut} + {@code XServerDisplayActivity}:
 * screenSize, graphicsDriver, dxwrapper, wincomponents, envVars, forceFullscreen.
 *
 * <p>Nucleo sem dependencia de Android: o mesmo codigo roda no aparelho e nos testes
 * locais (ver {@code runner/patches/tests}).
 */
public final class HubGameProfiles {

    /** Marcador da secao de dados extras dentro do arquivo .desktop. */
    public static final String EXTRA_SECTION = "[Extra Data]";

    /** Marcador de secao do desktop entry. */
    public static final String DESKTOP_SECTION = "[Desktop Entry]";

    private HubGameProfiles() {
    }

    /** Perfil aplicavel a um executavel de jogo. */
    public static final class Profile {
        /** Nome do executavel, em minusculas. */
        public final String name;
        /** Tamanho de tela desejado (ex.: {@code 800x600}) ou null. */
        public final String screenSize;
        /** Variaveis de ambiente extras (ex.: {@code WINEESYNC=0}) ou null. */
        public final String envVars;
        /** Wrapper D3D desejado (ex.: {@code dxvk}) ou null. */
        public final String dxwrapper;
        /** Driver grafico desejado (ex.: {@code vortek,gladio}) ou null. */
        public final String graphicsDriver;

        Profile(String name, String screenSize, String envVars, String dxwrapper, String graphicsDriver) {
            this.name = name;
            this.screenSize = screenSize;
            this.envVars = envVars;
            this.dxwrapper = dxwrapper;
            this.graphicsDriver = graphicsDriver;
        }

        /** Monta o conteudo da secao [Extra Data] deste perfil. */
        public String toExtraData() {
            StringBuilder sb = new StringBuilder();
            appendIfSet(sb, "screenSize", screenSize);
            appendIfSet(sb, "dxwrapper", dxwrapper);
            appendIfSet(sb, "graphicsDriver", graphicsDriver);
            appendIfSet(sb, "envVars", envVars);
            return sb.toString();
        }

        private static void appendIfSet(StringBuilder sb, String key, String value) {
            if (value != null && !value.isEmpty()) {
                sb.append(key).append('=').append(value).append('\n');
            }
        }
    }

    /**
     * Perfil padrao de um executavel.
     *
     * <p>Regras:
     * <ul>
     *   <li>{@code gta_sa.exe}: 800x600. O GTA San Andreas original so conhece modos
     *       4:3 (640x480, 800x600, 1024x768). Com o container em 1280x720 o pedido
     *       do modo 800x600x32 falha (o proprio exe traz a string
     *       {@code "Cannot find 800x600x32 video mode"}) e o jogo cai em uma janela
     *       800x600 no canto da tela. Em 800x600 o modo existe e o jogo entra
     *       fullscreen de verdade.</li>
     *   <li>Demais .exe: 1280x720 (padrao 16:9 do container).</li>
     * </ul>
     */
    public static Profile profileFor(String executableName) {
        String exe = executableName == null ? "" : executableName.toLowerCase(Locale.ENGLISH);

        if (exe.equals("gta_sa.exe") || exe.equals("gta_sa1.exe") || exe.equals("gta_sa2.exe")) {
            // WINEESYNC=0: com ESYNC ligado o jogo engasga (medido no aparelho).
            return new Profile(exe, "800x600", "WINEESYNC=0", null, null);
        }

        return new Profile(exe, "1280x720", null, null, null);
    }

    /**
     * Gera o arquivo {@code .desktop} de um atalho.
     *
     * <p>O formato e o que o upstream do Winlator le em {@code Shortcut}: secao
     * {@code [Desktop Entry]} com {@code Exec=wine <caminho>} e, quando ha perfil,
     * a secao {@code [Extra Data]} com as preferencias do jogo.
     *
     * @param executablePath caminho unix do executavel (usado no nome)
     * @param dosPath       caminho no espaco do Wine (ex.: {@code C:\\games\\gta_sa.exe})
     * @param profile       perfil do jogo (pode ser null)
     */
    public static String buildDesktopEntry(String executablePath, String dosPath, Profile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(DESKTOP_SECTION).append('\n');
        sb.append("Type=Application\n");
        sb.append("Name=").append(nameOf(executablePath)).append('\n');
        // Sem aspas: o upstream extrai o caminho com
        // execArgs.substring(execArgs.lastIndexOf("wine ") + 4) seguido de um unescape
        // proprio; com aspas o caminho sairia com as aspas e o upstream nao acharia
        // o executavel. Ver runner/patches/tests.
        sb.append("Exec=wine ").append(escapeExecPath(dosPath)).append('\n');
        sb.append("StartupNotify=true\n");

        String extra = profile == null ? "" : profile.toExtraData();
        if (!extra.isEmpty()) {
            sb.append('\n');
            sb.append(EXTRA_SECTION).append('\n');
            sb.append(extra);
        }
        return sb.toString();
    }

    /**
     * Escapa um caminho para a linha Exec de um .desktop.
     *
     * <p>Segue o {@code escape()} do Wine ({@code programs/winemenubuilder}): cada
     * {@code \} vira quatro {@code \}; {@code "}, {@code `} e {@code $} viram
     * {@code \x}.
     */
    public static String escapeExecPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\\\\\");
                    break;
                case '"':
                case '`':
                case '$':
                    sb.append('\\');
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /** Nome do executavel a partir de um caminho unix. */
    public static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash == -1 ? path : path.substring(slash + 1);
    }

    /** Nomes de instaladores que nao sao jogos (para nao importar "setup.exe"). */
    private static final String[] NOT_GAMES = {
            "setup.exe", "install.exe", "unins000.exe", "uninstall.exe", "vcredist.exe",
            "vc_redist.exe", "dxsetup.exe", "dxwebsetup.exe", "dotnetfx.exe", "vcredist_x86.exe",
            "vcredist_x64.exe", "directx.exe", "openal.exe", "7z.exe", "unarc.exe"
    };

    /**
     * Verdadeiro quando o nome parece o de um jogo e nao de um instalador.
     *
     * <p>So {@code .exe} conta. Instaladores conhecidos (setup, vcredist, dxsetup...)
     * sao ignorados: eles nao sao jogos e poluiriam a lista de atalhos.
     */
    public static boolean isGameExecutable(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ENGLISH);
        if (!lower.endsWith(".exe")) return false;

        for (String notGame : NOT_GAMES) {
            if (lower.equals(notGame)) return false;
        }
        return true;
    }
}
