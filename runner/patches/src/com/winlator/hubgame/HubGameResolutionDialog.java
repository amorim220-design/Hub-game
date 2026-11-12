package com.winlator.hubgame;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.container.Shortcut;
import com.winlator.core.AppUtils;

import java.util.ArrayList;

/**
 * Dialogo de resolucao dentro do jogo.
 *
 * <p>Ideia: em vez de editar o container toda vez, o jogador abre o menu lateral
 * do jogo (o mesmo que ja tem "Toggle Fullscreen"), escolhe a resolucao e o
 * runner aplica na hora: grava {@code screenSize} no atalho do jogo, liga
 * {@code forceFullscreen} e reinicia a sessao.
 *
 * <p>Por que reiniciar: o Wine anuncia UM unico modo ao guest (o tamanho da
 * tela do X, definido pelo {@code screenSize} do container) e a tela do X e
 * criada no inicio da sessao. Trocar o modo, portanto, exige recriar a sessao.
 */
public final class HubGameResolutionDialog {

    /** Resolucoes oferecidas, em ordem. */
    private static final String[] RESOLUTIONS = {
            "640x480", "800x600", "1024x768", "1280x720", "1280x800", "1280x1024", "1600x900", "1920x1080"
    };

    private HubGameResolutionDialog() {
    }

    /**
     * Mostra o dialogo de resolucao.
     *
     * @param context  contexto (a atividade do jogo)
     * @param shortcut atalho do jogo (pode ser null quando o jogo foi aberto
     *                 direto pelo gerenciador de arquivos)
     */
    public static void show(final Context context, final Shortcut shortcut) {
        final ContentDialog dialog = new ContentDialog(context, R.layout.hub_game_resolution_dialog);
        dialog.setTitle(R.string.hub_game_resolution);
        dialog.setIcon(R.drawable.icon_display_settings);

        final Spinner spinner = dialog.getContentView().findViewById(R.id.HGBSResolution);

        ArrayList<String> items = new ArrayList<>();
        int selected = 0;
        String current = shortcut == null ? "" : shortcut.getExtra("screenSize", "");
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            items.add(RESOLUTIONS[i]);
            if (RESOLUTIONS[i].equals(current)) selected = i;
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items));
        spinner.setSelection(selected);

        dialog.setOnConfirmCallback(() -> {
            int index = spinner.getSelectedItemPosition();
            if (index < 0 || index >= RESOLUTIONS.length) return;

            String resolution = RESOLUTIONS[index];

            if (shortcut != null) {
                shortcut.putExtra("screenSize", resolution);
                // A tela do container tem de ser MAIOR que a janela do jogo para o
                // renderer escalar: com forceFullscreen a janela ocupa a tela do X
                // inteira e o renderer amplia a area util para a tela fisica.
                shortcut.putExtra("forceFullscreen", "1");
                shortcut.saveData();
            }

            // A sessao precisa recomecar: a tela do X (e o unico modo
            // anunciado ao guest) e criada no inicio da sessao.
            AppUtils.restartApplication(context);
        });

        dialog.show();
    }
}
