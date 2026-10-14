package br.com.koneg.checklist;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Roda de tempos em tempos (mesmo com o app fechado) e avisa quando uma OS nova
 * foi enviada para o técnico logado. Usa a mesma conta que entrou no app.
 */
public class AvisosWorker extends Worker {

    public AvisosWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences sp = Avisos.prefs(c);
        String rt = sp.getString("rt", ""), email = sp.getString("email", ""), chave = sp.getString("apiKey", ""), projeto = sp.getString("projeto", "");
        if (rt.isEmpty() || email.isEmpty() || chave.isEmpty() || projeto.isEmpty()) return Result.success();
        try {
            // 1) troca o "refresh token" da conta por um token de acesso
            JSONObject tok = new JSONObject(post("https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(chave, "UTF-8"),
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), "application/x-www-form-urlencoded", null));
            String id = tok.optString("id_token", tok.optString("access_token", ""));
            String novoRt = tok.optString("refresh_token", "");
            if (!novoRt.isEmpty() && !novoRt.equals(rt)) sp.edit().putString("rt", novoRt).apply();
            if (id.isEmpty()) return Result.success();

            // 2) busca as OS enviadas para este técnico
            JSONObject consulta = new JSONObject("{\"structuredQuery\":{\"from\":[{\"collectionId\":\"ordens\"}],\"where\":{\"fieldFilter\":{\"field\":{\"fieldPath\":\"tecnicoEmail\"},\"op\":\"EQUAL\",\"value\":{\"stringValue\":\"\"}}}}}");
            consulta.getJSONObject("structuredQuery").getJSONObject("where").getJSONObject("fieldFilter").getJSONObject("value").put("stringValue", email);
            JSONArray r = new JSONArray(post("https://firestore.googleapis.com/v1/projects/" + projeto + "/databases/(default)/documents:runQuery",
                consulta.toString(), "application/json", id));
            for (int i = 0; i < r.length(); i++) {
                JSONObject doc = r.getJSONObject(i).optJSONObject("document");
                if (doc == null) continue;
                JSONObject f = doc.optJSONObject("fields");
                if (f == null) continue;
                String status = txt(f, "status"), por = txt(f, "atribuidoPor"), em = txt(f, "atribuidoEm");
                if ("concluida".equals(status) || em.isEmpty() || por.isEmpty() || por.equalsIgnoreCase(email)) continue;
                String nome = doc.optString("name");
                String osId = nome.substring(nome.lastIndexOf('/') + 1);
                String texto = txt(f, "cliente");
                String prob = txt(f, "problema");
                if (!prob.isEmpty()) texto += (texto.isEmpty() ? "" : " · ") + prob.split("\n")[0];
                Avisos.notificar(c, "Nova OS " + txt(f, "numero") + " para você", texto, "#os-" + osId);
            }
        } catch (Exception ignorado) {
            // sem internet ou sessão expirada: tenta de novo na próxima rodada
        }
        return Result.success();
    }

    private static String txt(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static String post(String endereco, String corpo, String tipo, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endereco).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", tipo);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream o = c.getOutputStream()) { o.write(corpo.getBytes("UTF-8")); }
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
