package br.com.koneg.checklist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * App Koneg: abre o painel (check list, ordem de serviço, estoque) em tela cheia.
 * - Câmera e galeria para as fotos
 * - Compartilhar PDFs e arquivos (WhatsApp, e-mail, Drive…)
 * - Puxar a tela para baixo para atualizar; a tela não apaga enquanto o app está aberto
 * - Procura sozinho uma versão nova do app, baixa e pede só um toque para instalar
 */
public class MainActivity extends Activity {

    private static final String ENDERECO = "https://koneg-estoque.netlify.app/checklist/";
    private static final String DOMINIO = "koneg-estoque.netlify.app";
    private static final String ULTIMA_VERSAO = "https://api.github.com/repos/quintalbravo2026-dev/koneg-estoque/releases/latest";
    private static final String AUTORIDADE = BuildConfig.APPLICATION_ID + ".arquivos";
    private static final int PEDIDO_FOTO = 10;
    private static final int PEDIDO_PERMISSAO_INSTALAR = 11;
    private static final long INTERVALO_VERIFICACAO = 6L * 60 * 60 * 1000; // 6 horas

    private WebView web;
    private SwipeRefreshLayout puxar;
    private ValueCallback<Uri[]> retornoFotos;
    private Uri fotoCamera;
    private boolean usouCamera = false;
    private volatile boolean travado = false;

    private File apkNovo;
    private boolean verificando = false;
    private long ultimaVerificacao = 0;

    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        puxar = new SwipeRefreshLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFFF3F4F5);
        puxar.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        puxar.setColorSchemeColors(0xFFF2B705, 0xFF16191D);
        puxar.setOnChildScrollUpCallback((pai, filho) -> travado || web.getScrollY() > 0);
        puxar.setOnRefreshListener(() -> web.reload());
        setContentView(puxar);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " KonegChecklistApp/" + BuildConfig.VERSION_CODE);

        web.addJavascriptInterface(new Ponte(), "KonegApp");
        web.setDownloadListener((url, ua, cd, mime, tam) -> abrirFora(Uri.parse(url)));
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> retorno, FileChooserParams params) {
                if (retornoFotos != null) retornoFotos.onReceiveValue(null);
                retornoFotos = retorno;
                boolean varias = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                escolherFoto(varias);
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if (host != null && (host.equals(DOMINIO) || host.endsWith("firebaseapp.com") || host.endsWith("googleapis.com"))) {
                    return false;
                }
                abrirFora(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                puxar.setRefreshing(false);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError erro) {
                if (req.isForMainFrame()) {
                    puxar.setRefreshing(false);
                    String html = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                        + "<body style=\"font-family:sans-serif;padding:32px;background:#f3f4f5;color:#16191d\">"
                        + "<h2>Sem conexão</h2><p>Abra o app uma vez com internet para ele guardar uma cópia no celular. "
                        + "Depois disso ele funciona mesmo sem sinal.</p>"
                        + "<p><a href=\"" + ENDERECO + "\" style=\"display:inline-block;padding:12px 18px;background:#f2b705;color:#1a1500;"
                        + "border-radius:6px;text-decoration:none;font-weight:bold\">Tentar de novo</a></p></body></html>";
                    v.loadDataWithBaseURL(ENDERECO, html, "text/html", "UTF-8", null);
                }
            }
        });

        if (salvo != null) web.restoreState(salvo); else web.loadUrl(ENDERECO);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (System.currentTimeMillis() - ultimaVerificacao > INTERVALO_VERIFICACAO) verificarAtualizacao(false);
    }

    private void abrirFora(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignorado) { }
    }

    // ---------------- Fotos: câmera ou galeria ----------------
    private void escolherFoto(boolean varias) {
        new AlertDialog.Builder(this)
            .setTitle("Adicionar foto")
            .setItems(new CharSequence[]{"Tirar foto agora", "Escolher da galeria"}, (d, qual) -> {
                if (qual == 0) abrirCamera(); else abrirGaleria(varias);
            })
            .setOnCancelListener(d -> devolverFotos(null))
            .show();
    }

    private void abrirCamera() {
        try {
            File pasta = new File(getCacheDir(), "fotos");
            if (!pasta.exists()) pasta.mkdirs();
            File arq = File.createTempFile("foto_", ".jpg", pasta);
            fotoCamera = FileProvider.getUriForFile(this, AUTORIDADE, arq);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, fotoCamera);
            cam.setClipData(ClipData.newRawUri("foto", fotoCamera));
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            usouCamera = true;
            startActivityForResult(cam, PEDIDO_FOTO);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir a câmera.", Toast.LENGTH_LONG).show();
            devolverFotos(null);
        }
    }

    private void abrirGaleria(boolean varias) {
        try {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("image/*");
            if (varias) g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            usouCamera = false;
            startActivityForResult(Intent.createChooser(g, "Escolher foto"), PEDIDO_FOTO);
        } catch (Exception e) {
            devolverFotos(null);
        }
    }

    private void devolverFotos(Uri[] fotos) {
        if (retornoFotos != null) retornoFotos.onReceiveValue(fotos);
        retornoFotos = null;
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        if (pedido == PEDIDO_FOTO) {
            Uri[] fotos = null;
            if (resultado == RESULT_OK) {
                if (usouCamera) {
                    if (fotoCamera != null) fotos = new Uri[]{fotoCamera};
                } else if (dados != null) {
                    ClipData cd = dados.getClipData();
                    if (cd != null && cd.getItemCount() > 0) {
                        fotos = new Uri[cd.getItemCount()];
                        for (int i = 0; i < cd.getItemCount(); i++) fotos[i] = cd.getItemAt(i).getUri();
                    } else if (dados.getData() != null) {
                        fotos = new Uri[]{dados.getData()};
                    }
                }
            }
            devolverFotos(fotos);
        } else if (pedido == PEDIDO_PERMISSAO_INSTALAR) {
            if (podeInstalar() && apkNovo != null) instalar();
        }
    }

    // ---------------- Ponte com a página ----------------
    private class Ponte {
        @JavascriptInterface
        public void imprimir(final String titulo) {
            runOnUiThread(() -> {
                String nome = (titulo == null || titulo.isEmpty()) ? "Koneg" : titulo;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adaptador = web.createPrintDocumentAdapter(nome);
                PrintAttributes atr = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build();
                pm.print(nome, adaptador, atr);
            });
        }

        /** Recebe um arquivo (PDF, imagem, backup…) em base64 e abre a tela de compartilhar do Android. */
        @JavascriptInterface
        public void compartilharArquivo(final String base64, final String nome, final String tipo, final String texto) {
            new Thread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    File pasta = new File(getCacheDir(), "compartilhar");
                    if (!pasta.exists()) pasta.mkdirs();
                    String limpo = (nome == null || nome.isEmpty()) ? "arquivo" : nome.replaceAll("[\\\\/:*?\"<>|]", "-");
                    File arq = new File(pasta, limpo);
                    try (OutputStream o = new FileOutputStream(arq)) { o.write(bytes); }
                    Uri u = FileProvider.getUriForFile(MainActivity.this, AUTORIDADE, arq);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(tipo == null || tipo.isEmpty() ? "application/octet-stream" : tipo);
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    if (texto != null && !texto.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, texto);
                    i.setClipData(ClipData.newRawUri(limpo, u));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> {
                        try { startActivity(Intent.createChooser(i, "Enviar " + limpo)); }
                        catch (Exception e) { Toast.makeText(MainActivity.this, "Nenhum app para compartilhar.", Toast.LENGTH_LONG).show(); }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Não foi possível preparar o arquivo.", Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        /** Enquanto alguém assina com o dedo, puxar para atualizar fica desligado. */
        @JavascriptInterface
        public void travarAtualizar(final boolean travar) {
            travado = travar;
            runOnUiThread(() -> puxar.setEnabled(!travar));
        }

        @JavascriptInterface
        public String versao() {
            return String.valueOf(BuildConfig.VERSION_CODE);
        }

        @JavascriptInterface
        public void procurarAtualizacao() {
            runOnUiThread(() -> verificarAtualizacao(true));
        }
    }

    // ---------------- Atualização automática ----------------
    private void verificarAtualizacao(final boolean manual) {
        if (verificando) return;
        verificando = true;
        if (manual) Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(baixarTexto(ULTIMA_VERSAO));
                int nova = Integer.parseInt("0" + j.optString("tag_name", "").replaceAll("\\D", ""));
                String url = null; long tamanho = 0;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); tamanho = a.optLong("size"); break; }
                    }
                }
                ultimaVerificacao = System.currentTimeMillis();
                if (nova > BuildConfig.VERSION_CODE && url != null) {
                    File pasta = new File(getCacheDir(), "atualizacao");
                    if (!pasta.exists()) pasta.mkdirs();
                    File arq = new File(pasta, "koneg-" + nova + ".apk");
                    if (!arq.exists() || (tamanho > 0 && arq.length() != tamanho)) baixarArquivo(url, arq);
                    final String notas = j.optString("body", "");
                    final int v = nova;
                    runOnUiThread(() -> { apkNovo = arq; perguntarInstalar(v, notas); });
                } else if (manual) {
                    runOnUiThread(() -> Toast.makeText(this, "O app já está na versão mais nova.", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> Toast.makeText(this, "Não foi possível procurar atualização agora.", Toast.LENGTH_LONG).show());
            } finally {
                verificando = false;
            }
        }).start();
    }

    private void perguntarInstalar(int versao, String notas) {
        if (isFinishing()) return;
        String msg = "A versão " + versao + " do app Koneg já foi baixada e está pronta para instalar.";
        if (notas != null && !notas.trim().isEmpty()) msg += "\n\nNovidades:\n" + (notas.length() > 600 ? notas.substring(0, 600) + "…" : notas.trim());
        new AlertDialog.Builder(this)
            .setTitle("Atualização disponível")
            .setMessage(msg)
            .setPositiveButton("Instalar agora", (d, w) -> instalar())
            .setNegativeButton("Depois", null)
            .show();
    }

    private boolean podeInstalar() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void instalar() {
        if (apkNovo == null || !apkNovo.exists()) return;
        if (!podeInstalar()) {
            new AlertDialog.Builder(this)
                .setTitle("Permitir atualizações")
                .setMessage("Para o app Koneg conseguir se atualizar, ative \"Permitir desta fonte\" na próxima tela e depois volte.")
                .setPositiveButton("Abrir configuração", (d, w) -> {
                    try {
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), PEDIDO_PERMISSAO_INSTALAR);
                    } catch (Exception e) { Toast.makeText(this, "Abra as configurações do app e permita instalar apps.", Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Agora não", null)
                .show();
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(this, AUTORIDADE, apkNovo);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir o instalador.", Toast.LENGTH_LONG).show();
        }
    }

    private static HttpURLConnection conectar(String endereco) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endereco).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "KonegApp/" + BuildConfig.VERSION_CODE);
        c.setRequestProperty("Accept", "application/vnd.github+json, */*");
        return c;
    }

    private static String baixarTexto(String endereco) throws Exception {
        HttpURLConnection c = conectar(endereco);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static void baixarArquivo(String endereco, File destino) throws Exception {
        File parte = new File(destino.getPath() + ".parte");
        HttpURLConnection c = conectar(endereco);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(parte)) {
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { c.disconnect(); }
        if (destino.exists()) destino.delete();
        if (!parte.renameTo(destino)) throw new Exception("renomear");
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
