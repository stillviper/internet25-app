package online.internet25.comercio;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/** Avisa si hay una versión nueva en mi.internet25.online/app/version.json ({"code":2,"name":"1.1","url":"…apk","nota":"…"}). */
final class Updater {
    static void check(MainActivity a, String url) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                if (c.getResponseCode() != 200) return;
                String body;
                try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) { body = s.hasNext() ? s.next() : ""; }
                JSONObject j = new JSONObject(body);
                long code = j.optLong("code", 0);
                String apk = j.optString("url", "");
                if (code > BuildConfigLite.versionCode(a) && apk.startsWith("https://" + MainActivity.HOST + "/")) {
                    a.runOnUiThread(() -> new AlertDialog.Builder(a)
                            .setTitle("Hay una versión nueva")
                            .setMessage("Internet25 " + j.optString("name", "") + "\n" + j.optString("nota", ""))
                            .setPositiveButton("Descargar", (d, w) -> a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk))))
                            .setNegativeButton("Después", null).show());
                }
            } catch (Exception ignored) { }
        }).start();
    }
}
