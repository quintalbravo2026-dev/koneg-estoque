package br.com.koneg.checklist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Resposta do instalador do Android ao atualizar o app. */
public class InstalarReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // O Android pediu confirmação: abre a tela "Atualizar este app?"
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) {
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirmar); } catch (Exception ignorado) { }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Avisos.prefs(c).edit().putBoolean("silenciosoFalhou", true).apply();
        }
    }
}
