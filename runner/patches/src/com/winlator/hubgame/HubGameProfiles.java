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
        /** Tamanho de tela desejado (ex.: {@code 1280x720}) ou null. */
        public final String screenSize;
        /** Variaveis de ambiente extras (ex.: {@code WINEESYNC=0}) ou null. */
        public final String envVars;
        /** Wrapper D3D desejado (ex.: {@code dxvk}) ou null. */
        public final String dxwrapper;
        /** Driver grafico desejado (ex.: {@code vortek,gladio}) ou null. */
        public final String graphicsDriver;
        /** "1" para forcar a janela do jogo em tela cheia, ou null. */
        public final String forceFullscreen;
        /** Id do perfil de controles na tela (ver assets/inputcontrols/profiles) ou null. */
        public final String controlsProfile;

        Profile(String name, String screenSize, String envVars, String dxwrapper, String graphicsDriver,
                String forceFullscreen, String controlsProfile) {
            this.name = name;
            this.screenSize = screenSize;
            this.envVars = envVars;
            this.dxwrapper = dxwrapper;
            this.graphicsDriver = graphicsDriver;
            this.forceFullscreen = forceFullscreen;
            this.controlsProfile = controlsProfile;
        }

        /** Monta o conteudo da secao [Extra Data] deste perfil. */
        public String toExtraData() {
            StringBuilder sb = new StringBuilder();
            appendIfSet(sb, "screenSize", screenSize);
            appendIfSet(sb, "dxwrapper", dxwrapper);
            appendIfSet(sb, "graphicsDriver", graphicsDriver);
            appendIfSet(sb, "envVars", envVars);
            appendIfSet(sb, "forceFullscreen", forceFullscreen);
            appendIfSet(sb, "controlsProfile", controlsProfile);
            return sb.toString();
        }

        private static void appendIfSet(StringBuilder sb, String key, String value) {
            if (value != null && !value.isEmpty()) {
                sb.append(key).append('=').append(value).append('\n');
            }
        }
    }

    /**
     * Id do perfil de controles "FPS" (assets/inputcontrols/profiles/controls-4.icp).
     *
     * <p>E o unico perfil que traz {@code mouseMoveMode}: o toque move o mouse
     * de verdade, alem dos botoes de teclado. E o que um jogo de PC com mouse
     * (olhar, mirar) precisa - o NFSU2, por exemplo, usa o mouse para olhar e
     * o teclado para acelerar.
     */
    public static final String CONTROLS_PROFILE_FPS = "4";

    /**
     * Perfil padrao de um executavel.
     *
     * <p>Duas coisas importam aqui:
     *
     * <ol>
     *   <li><b>Nao forcar resolucao.</b> O container ja vem em 1280x720 e muitos
     *       jogos antigos tem uma lista fixa de modos: se o modo pedido nao
     *       existir, o jogo simplesmente sai (no aparelho, o NFSU2 fechava
     *       sozinho com 1280x720, que nao esta na lista dele - so 640x480,
     *       800x600, 1024x768, 1280x960, 1280x1024).</li>
     *   <li><b>Nao deixar a tela do container com o mesmo tamanho do modo do
     *       jogo.</b> O upstream so escala a janela do jogo quando ela e
     *       MENOR que a tela (GLRenderer: {@code width < screenInfo.width}).
     *       Com a tela em 800x600 e o jogo tambem em 800x600, nada e escalado:
     *       a imagem fica pequena no meio. Com a tela em 1280x720, o jogo em
     *       800x600 e o {@code forceFullscreen} ligado, a imagem e escalada
     *       para 960x720 centralizado.</li>
     * </ol>
     *
     * <p>Por isso o perfil usa {@code screenSize=1280x720} (a resolucao
     * padrao do container, maior que o modo 4:3 do jogo) e
     * {@code forceFullscreen=1}, em vez de 800x600.
     */
    public static Profile profileFor(String executableName) {
        String exe = executableName == null ? "" : executableName.toLowerCase(Locale.ENGLISH);

        // Need for Speed Underground 2 e os demais jogos da EA da era 2004:
        // lista fixa de modos, todos 4:3 (640x480, 800x600, 1024x768,
        // 1280x960, 1280x1024). Jogo de mouse: olhar e acelerar.
        if (exe.equals("speed2.exe")) {
            return new Profile(exe, "1280x720", "WINEESYNC=0", null, null, "1", CONTROLS_PROFILE_FPS);
        }

        // GTA SA: o proprio exe traz "Cannot find 800x600x32 video mode" quando
        // o modo 800x600 nao existe, e a tela precisa ser maior que o jogo
        // para o forceFullscreen escalar.
        if (exe.equals("gta_sa.exe") || exe.equals("gta_sa1.exe") || exe.equals("gta_sa2.exe")) {
            return new Profile(exe, "1280x720", "WINEESYNC=0", null, null, "1", CONTROLS_PROFILE_FPS);
        }

        // Sem resolucao forcada e sem forcar fullscreen: o container mantem o
        // padrao dele e o jogo abre como quiser.
        return new Profile(exe, null, null, null, null, null, null);
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
     * @param displayName   nome do jogo como aparece no launcher
     * @param profile       perfil do jogo (pode ser null)
     */
    public static String buildDesktopEntry(String executablePath, String dosPath, String displayName, Profile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(DESKTOP_SECTION).append('\n');
        sb.append("Type=Application\n");
        sb.append("Name=").append(displayName).append('\n');
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
