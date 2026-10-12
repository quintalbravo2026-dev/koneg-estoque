package br.com.koneg.checklist;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** App Check List Koneg: abre a página do check list numa tela cheia. */
public class MainActivity extends Activity {

    private static final String ENDERECO = "https://koneg-estoque.netlify.app/checklist/";
    private static final String DOMINIO = "koneg-estoque.netlify.app";

    private WebView web;

    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        web = new WebView(this);
        web.setBackgroundColor(0xFFF3F4F5);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " KonegChecklistApp");

        web.addJavascriptInterface(new Ponte(), "KonegApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if (host != null && (host.equals(DOMINIO) || host.endsWith("firebaseapp.com") || host.endsWith("googleapis.com"))) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignorado) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError erro) {
                if (req.isForMainFrame()) {
                    String html = "<html><body style=\"font-family:sans-serif;padding:32px;background:#f3f4f5;color:#16191d\">"
                        + "<h2>Sem conexão</h2><p>O Check List precisa de internet. Verifique o Wi-Fi ou os dados móveis.</p>"
                        + "<p><a href=\"" + ENDERECO + "\" style=\"display:inline-block;padding:12px 18px;background:#f2b705;color:#1a1500;"
                        + "border-radius:6px;text-decoration:none;font-weight:bold\">Tentar de novo</a></p></body></html>";
                    v.loadDataWithBaseURL(ENDERECO, html, "text/html", "UTF-8", null);
                }
            }
        });

        if (salvo != null) web.restoreState(salvo); else web.loadUrl(ENDERECO);
    }

    /** Recursos que a página pode chamar: window.KonegApp.imprimir(titulo). */
    private class Ponte {
        @JavascriptInterface
        public void imprimir(final String titulo) {
            runOnUiThread(() -> {
                String nome = (titulo == null || titulo.isEmpty()) ? "Check List" : titulo;
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adaptador = web.createPrintDocumentAdapter(nome);
                PrintAttributes atr = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build();
                pm.print(nome, adaptador, atr);
            });
        }
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
