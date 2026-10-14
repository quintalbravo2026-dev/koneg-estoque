package br.com.koneg.checklist;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Avisos no celular (ex.: "Nova OS para você"). Cada aviso aparece uma vez só. */
final class Avisos {
    static final String PREFS = "koneg";
    private static final String CANAL = "os";

    private Avisos() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void notificar(Context c, String titulo, String texto, String destino) {
        SharedPreferences sp = prefs(c);
        String chave = destino == null ? titulo : destino;
        List<String> ja = new ArrayList<>(Arrays.asList(sp.getString("avisadas", "").split("\n")));
        if (ja.contains(chave)) return;
        ja.add(chave);
        while (ja.size() > 100) ja.remove(0);
        sp.edit().putString("avisadas", String.join("\n", ja)).apply();

        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CANAL) == null) {
                NotificationChannel ch = new NotificationChannel(CANAL, "Ordens de serviço", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Aviso quando uma OS é enviada para você");
                nm.createNotificationChannel(ch);
            }
        }
        Intent abrir = new Intent(c, MainActivity.class);
        abrir.putExtra("destino", destino == null ? "" : destino);
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, chave.hashCode(), abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setColor(0xFFF2B705)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);
        try { NotificationManagerCompat.from(c).notify(chave.hashCode(), b.build()); } catch (SecurityException ignorado) { }
    }
}
