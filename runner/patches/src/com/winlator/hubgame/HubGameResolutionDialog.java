package com.winlator.hubgame;

import android.app.Activity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.container.Shortcut;

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
            "640x480", "800x600", "1024x768", "1280x960"
    };

    private HubGameResolutionDialog() {
    }

    /**
     * Mostra o dialogo de resolucao.
     *
     * @param activity contexto (a atividade do jogo)
     * @param shortcut atalho do jogo (pode ser null quando o jogo foi aberto
     *                 direto pelo gerenciador de arquivos)
     */
    public static void show(final Activity activity, final Shortcut shortcut) {
        final ContentDialog dialog = new ContentDialog(activity, R.layout.hub_game_resolution_dialog);
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

        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items));
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
            // Preserve the current shortcut_path/container_id intent. Restarting the
            // whole application loses the game shortcut and returns to the launcher.
            ((XServerDisplayActivity) activity).restartForHubGameResolution();
        });

        dialog.show();
    }
}
