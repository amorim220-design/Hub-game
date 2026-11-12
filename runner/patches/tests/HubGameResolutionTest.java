package com.winlator.hubgame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Harness da logica do dialogo de resolucao.
 *
 * <p>Reproduz as regras do dialogo (HubGameResolutionDialog) sem Android:
 * a lista de resolucoes, a selecao do item atual e o que e gravado no
 * atalho quando o jogador confirma.
 */
public final class HubGameResolutionTest {

    /** Resolucoes oferecidas, em ordem (espelha HubGameResolutionDialog). */
    static final String[] RESOLUTIONS = {
            "640x480", "800x600", "1024x768", "1280x720", "1280x800", "1280x1024", "1600x900", "1920x1080"
    };

    /** Reproduz a montagem da lista e a selecao inicial. */
    static int selectedIndex(String currentScreenSize) {
        List<String> items = new ArrayList<>(Arrays.asList(RESOLUTIONS));
        int selected = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equals(currentScreenSize)) selected = i;
        }
        return selected;
    }

    /** Reproduz o que o dialogo grava no atalho. */
    static List<String> extrasWritten(String resolution) {
        List<String> extras = new ArrayList<>();
        extras.add("screenSize=" + resolution);
        extras.add("forceFullscreen=1");
        return extras;
    }

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
        // O atalho do NFSU2 tem 800x600 -> o dialogo abre nessa.
        check("selecao inicial com 800x600", "1", String.valueOf(selectedIndex("800x600")));
        check("selecao inicial com 1280x720", "3", String.valueOf(selectedIndex("1280x720")));
        check("selecao inicial sem valor", "0", String.valueOf(selectedIndex("")));

        // Confirmar grava a resolucao escolhida e liga o fullscreen.
        check("grava screenSize", "screenSize=1920x1080", extrasWritten("1920x1080").get(0));
        check("grava forceFullscreen", "forceFullscreen=1", extrasWritten("1920x1080").get(1));

        // As resolucoes oferecidas tem de incluir as que os jogos 4:3 aceitam.
        check("inclui 800x600", true, Arrays.asList(RESOLUTIONS).contains("800x600"));
        check("inclui 1024x768", true, Arrays.asList(RESOLUTIONS).contains("1024x768"));
        check("inclui 1280x1024", true, Arrays.asList(RESOLUTIONS).contains("1280x1024"));
        check("nao inclui resolucao invalida", false,
                Arrays.asList(RESOLUTIONS).contains("1920x108"));

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
