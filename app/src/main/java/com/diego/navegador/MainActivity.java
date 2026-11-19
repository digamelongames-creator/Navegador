package com.diego.navegador;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String INICIO = "https://www.google.com";
    private static final String PREFS = "ajustes";
    private static final String KEY_BLOQUEADAS = "bloqueadas";
    private static final String KEY_INICIADA = "iniciada";
    private static final int REQ_ARCHIVO = 1;

    private WebView web;
    private EditText barra;
    private ProgressBar progreso;
    private SharedPreferences prefs;
    private volatile Set<String> bloqueadas = Collections.emptySet();
    private ValueCallback<Uri[]> archivoCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_INICIADA, false)) {
            Set<String> inicial = new HashSet<>();
            inicial.add("instagram.com");
            prefs.edit().putStringSet(KEY_BLOQUEADAS, inicial).putBoolean(KEY_INICIADA, true).apply();
        }
        bloqueadas = new HashSet<>(prefs.getStringSet(KEY_BLOQUEADAS, Collections.<String>emptySet()));

        web = findViewById(R.id.web);
        barra = findViewById(R.id.barra);
        progreso = findViewById(R.id.progreso);
        Button atras = findViewById(R.id.atras);
        Button recargar = findViewById(R.id.recargar);
        Button menu = findViewById(R.id.menu);

        configurarWeb();

        barra.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                abrir(barra.getText().toString());
                return true;
            }
            return false;
        });

        atras.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });
        recargar.setOnClickListener(v -> web.reload());
        menu.setOnClickListener(this::mostrarMenu);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (!abrirIntent(getIntent())) {
            web.loadUrl(INICIO);
        }
    }

    // ---------- Configuración del WebView ----------

    private void configurarWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return gestionarEnlace(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (estaBloqueada(host)) {
                    return respuesta(request.isForMainFrame() ? paginaBloqueada(host) : "");
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!barra.hasFocus()) barra.setText(url);
                progreso.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progreso.setVisibility(View.GONE);
                if (!barra.hasFocus()) barra.setText(url);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progreso.setProgress(p);
                if (p >= 100) progreso.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (archivoCallback != null) archivoCallback.onReceiveValue(null);
                archivoCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_ARCHIVO);
                } catch (Exception e) {
                    archivoCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Por si una web bloqueada intenta servirse desde caché (service worker)
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                if (estaBloqueada(request.getUrl().getHost())) return respuesta("");
                return null;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mime, length) -> {
            try {
                String nombre = URLUtil.guessFileName(url, contentDisposition, mime);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) r.addRequestHeader("Cookie", cookies);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Descargando " + nombre, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo descargar", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Enlaces que no son web (teléfono, correo, apps...) se abren fuera. */
    private boolean gestionarEnlace(Uri u) {
        String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        switch (esquema) {
            case "http":
            case "https":
            case "about":
            case "data":
            case "blob":
            case "javascript":
                return false;
        }
        Intent intent = null;
        try {
            if (esquema.equals("intent")) {
                intent = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, u);
            }
            startActivity(intent);
        } catch (Exception e) {
            String alternativa = intent != null ? intent.getStringExtra("browser_fallback_url") : null;
            if (alternativa != null) {
                web.loadUrl(alternativa);
            } else {
                Toast.makeText(this, "No hay ninguna app para abrir este enlace", Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    // ---------- Bloqueo ----------

    private boolean estaBloqueada(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        Set<String> lista = bloqueadas;
        for (String d : lista) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    private WebResourceResponse respuesta(String html) {
        return new WebResourceResponse("text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private String paginaBloqueada(String host) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;"
                + "height:90vh;margin:0;background:#fff;color:#202124;text-align:center}"
                + ".c{padding:24px}.i{font-size:64px}h1{font-size:22px;margin:12px 0 8px}"
                + "p{color:#5f6368;font-size:15px}</style></head><body><div class='c'>"
                + "<div class='i'>&#128683;</div><h1>Página bloqueada</h1>"
                + "<p><b>" + TextUtils.htmlEncode(host) + "</b> está en tu lista de webs bloqueadas.</p>"
                + "</div></body></html>";
    }

    private void editarBloqueadas() {
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setMinLines(5);
        campo.setGravity(Gravity.TOP | Gravity.START);
        List<String> orden = new ArrayList<>(bloqueadas);
        Collections.sort(orden);
        campo.setText(TextUtils.join("\n", orden));

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout marco = new FrameLayout(this);
        marco.setPadding(pad, pad / 2, pad, 0);
        marco.addView(campo);

        new AlertDialog.Builder(this)
                .setTitle("Webs bloqueadas")
                .setMessage("Una por línea. Ejemplo: instagram.com\nTambién bloquea sus subdominios.")
                .setView(marco)
                .setPositiveButton("Guardar", (d, w) -> guardarBloqueadas(campo.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void guardarBloqueadas(String texto) {
        Set<String> nuevas = new LinkedHashSet<>();
        for (String linea : texto.split("[\\s,;]+")) {
            String d = limpiarDominio(linea);
            if (!d.isEmpty()) nuevas.add(d);
        }
        List<String> quitadas = new ArrayList<>();
        for (String d : bloqueadas) {
            if (!nuevas.contains(d)) quitadas.add(d);
        }
        if (quitadas.isEmpty()) {
            aplicar(nuevas);
            return;
        }
        // Un pequeño freno antes de desbloquear algo
        new AlertDialog.Builder(this)
                .setTitle("¿Desbloquear?")
                .setMessage("Vas a desbloquear:\n\n" + TextUtils.join("\n", quitadas) + "\n\n¿Seguro?")
                .setPositiveButton("Sí, desbloquear", (d, w) -> aplicar(nuevas))
                .setNegativeButton("No", null)
                .show();
    }

    private void aplicar(Set<String> nuevas) {
        bloqueadas = new HashSet<>(nuevas);
        prefs.edit().putStringSet(KEY_BLOQUEADAS, new HashSet<>(nuevas)).apply();
        Toast.makeText(this, "Lista guardada", Toast.LENGTH_SHORT).show();
        web.reload();
    }

    private static String limpiarDominio(String s) {
        String d = s.trim().toLowerCase();
        d = d.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        d = d.split("[/?#:]", 2)[0];
        if (d.startsWith("www.")) d = d.substring(4);
        while (d.startsWith(".")) d = d.substring(1);
        return d;
    }

    // ---------- Navegación ----------

    private void abrir(String texto) {
        String t = texto.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !t.matches("(?i)^[^\\s/]+\\.[^\\s/]+:\\d+.*")) {
            url = t;
        } else if (!t.contains(" ") && t.contains(".")) {
            url = "https://" + t;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(t);
        }
        ocultarTeclado();
        web.loadUrl(url);
    }

    private void ocultarTeclado() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(barra.getWindowToken(), 0);
        barra.clearFocus();
        web.requestFocus();
    }

    private void mostrarMenu(View v) {
        PopupMenu p = new PopupMenu(this, v);
        p.getMenu().add(0, 1, 0, "Inicio");
        p.getMenu().add(0, 2, 1, "Adelante");
        p.getMenu().add(0, 3, 2, "Webs bloqueadas");
        p.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    web.loadUrl(INICIO);
                    return true;
                case 2:
                    if (web.canGoForward()) web.goForward();
                    return true;
                case 3:
                    editarBloqueadas();
                    return true;
            }
            return false;
        });
        p.show();
    }

    private boolean abrirIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            abrir(i.getData().toString());
            return true;
        }
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        abrirIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ARCHIVO && archivoCallback != null) {
            archivoCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            archivoCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
