package online.internet25.comercio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.List;

/**
 * Internet25 Comercio: abre la pantalla del comercio de mi.internet25.online e imprime directo
 * en la impresora del equipo (Sunmi InnerPrinter) o en la mini impresora Bluetooth emparejada.
 * Las pantallas viven en la web: cualquier cambio en el panel aparece aquí sin reinstalar.
 */
public class MainActivity extends Activity {
    static final String HOST = "mi.internet25.online";
    static final String START = "https://" + HOST + "/panel/?_route=plugin/i25_fichas";
    static final String VERSION_URL = "https://" + HOST + "/app/version.json";

    private WebView web;
    private Printer printer;
    private SharedPreferences prefs;
    private volatile String currentUrl = START;
    private boolean offline = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences("i25", MODE_PRIVATE);
        printer = new Printer(this, prefs);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " Internet25App/" + BuildConfigLite.versionName(this));
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Bridge(), "I25App");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return route(r.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return route(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                currentUrl = url;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                currentUrl = url;
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String d, String url) {
                if (url != null && url.equals(v.getUrl())) showOffline();
            }
        });

        askBluetoothPermission();
        if (b != null) web.restoreState(b); else web.loadUrl(START);
        Updater.check(this, VERSION_URL);
    }

    /** Solo nuestro dominio se abre dentro de la app; WhatsApp y otros enlaces van a su app. */
    private boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (("https".equals(scheme) || "http".equals(scheme)) && HOST.equalsIgnoreCase(u.getHost())) return false;
        if ("file".equals(scheme) || "about".equals(scheme)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
        return true;
    }

    private void showOffline() {
        offline = true;
        web.loadUrl("file:///android_asset/offline.html");
    }

    boolean onOurSite() {
        try { return HOST.equalsIgnoreCase(Uri.parse(currentUrl).getHost()); } catch (Exception e) { return false; }
    }

    private void askBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 7);
        }
    }

    @Override
    public void onBackPressed() {
        if (offline) { super.onBackPressed(); return; }
        // La página usa el historial para cerrar la ficha en pantalla y el menú.
        if (web.canGoBack()) { web.goBack(); return; }
        moveTaskToBack(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    void choosePrinterDialog() {
        List<String[]> list = printer.bonded();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Impresora")
                    .setMessage("No hay impresoras Bluetooth emparejadas. Empareja la mini impresora en Ajustes → Bluetooth y vuelve a intentar.")
                    .setPositiveButton("Abrir Bluetooth", (d, w) -> { try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { } })
                    .setNegativeButton("Cerrar", null).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i)[0] + "  (" + list.get(i)[1] + ")";
        new AlertDialog.Builder(this).setTitle("Elige la impresora")
                .setItems(names, (d, i) -> {
                    printer.setChosen(list.get(i)[1], list.get(i)[0]);
                    Toast.makeText(this, "Impresora: " + list.get(i)[0], Toast.LENGTH_SHORT).show();
                    web.evaluateJavascript("window.i25PrinterChanged&&i25PrinterChanged()", null);
                }).show();
    }

    /** Lo que la página puede pedirle a la app (solo cuando está en mi.internet25.online). */
    class Bridge {
        @JavascriptInterface
        public void printImage(String base64Png) {
            if (!onOurSite()) return;
            try {
                byte[] png = Base64.decode(base64Png, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                printer.enqueue(bmp, err -> runOnUiThread(() -> {
                    if (err != null) {
                        Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                        web.evaluateJavascript("window.i25PrintError&&i25PrintError(" + org.json.JSONObject.quote(err) + ")", null);
                    }
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No se pudo leer la ficha para imprimir", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public String printerName() {
            if (!onOurSite()) return "";
            return printer.describe();
        }

        @JavascriptInterface
        public void choosePrinter() {
            if (!onOurSite()) return;
            runOnUiThread(MainActivity.this::choosePrinterDialog);
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> { offline = false; web.loadUrl(START); });
        }

        @JavascriptInterface
        public String version() {
            return BuildConfigLite.versionName(MainActivity.this);
        }
    }
}
