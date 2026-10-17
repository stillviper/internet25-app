package online.internet25.comercio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impresión ESC/POS por Bluetooth clásico (SPP).
 * - Sunmi (V1/V2/V2s/V2 Pro/P1…): la impresora interna aparece ya emparejada como "InnerPrinter"
 *   (00:11:22:33:44:55) según el manual oficial de Sunmi. No hace falta instalar nada.
 * - Otros Android: usa la mini impresora emparejada en Ajustes → Bluetooth (se elige una vez).
 */
class Printer {
    interface Done { void done(String errorOrNull); }

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final String SUNMI_ADDR = "00:11:22:33:44:55";
    static final int WIDTH = 384; // 58 mm a 203 dpi

    private final MainActivity act;
    private final SharedPreferences prefs;
    private final ExecutorService q = Executors.newSingleThreadExecutor();
    private BluetoothSocket sock;
    private String sockAddr;

    Printer(MainActivity act, SharedPreferences prefs) {
        this.act = act;
        this.prefs = prefs;
    }

    private boolean allowed() {
        return Build.VERSION.SDK_INT < 31 || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    List<String[]> bonded() {
        List<String[]> out = new ArrayList<>();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !allowed()) return out;
        try {
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName() == null ? d.getAddress() : d.getName();
                String[] row = {n, d.getAddress()};
                if (isSunmi(d)) out.add(0, row); else out.add(row);
            }
        } catch (Exception ignored) { }
        return out;
    }

    @SuppressLint("MissingPermission")
    private static boolean isSunmi(BluetoothDevice d) {
        String n = d.getName() == null ? "" : d.getName();
        return SUNMI_ADDR.equalsIgnoreCase(d.getAddress()) || n.toLowerCase().contains("innerprinter");
    }

    void setChosen(String addr, String name) {
        prefs.edit().putString("addr", addr).putString("name", name).apply();
        closeQuietly();
    }

    String describe() {
        String a = pickAddress();
        if (a == null) return "";
        for (String[] r : bonded()) if (r[1].equalsIgnoreCase(a)) return r[0];
        return a;
    }

    /** Orden: la elegida por el comercio → la interna del Sunmi → la única impresora emparejada. */
    @SuppressLint("MissingPermission")
    private String pickAddress() {
        List<String[]> b = bonded();
        String saved = prefs.getString("addr", null);
        if (saved != null) for (String[] r : b) if (r[1].equalsIgnoreCase(saved)) return saved;
        for (String[] r : b) if (SUNMI_ADDR.equalsIgnoreCase(r[1]) || r[0].toLowerCase().contains("innerprinter")) return r[1];
        String only = null; int n = 0;
        for (String[] r : b) {
            String name = r[0].toLowerCase();
            if (name.matches(".*(print|pt-|mtp|pos|58|thermal|rpp|goojprt|xp-|bt).*")) { only = r[1]; n++; }
        }
        return n == 1 ? only : null;
    }

    void enqueue(Bitmap bmp, Done cb) {
        q.execute(() -> {
            String err = printNow(bmp);
            if (err != null) { closeQuietly(); err = printNow(bmp) == null ? null : err; } // un reintento con conexión nueva
            cb.done(err);
        });
    }

    @SuppressLint("MissingPermission")
    private String printNow(Bitmap bmp) {
        if (!allowed()) return "Falta permiso de Bluetooth. Ábrelo en Ajustes → Apps → Internet25 → Permisos.";
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return "Este equipo no tiene Bluetooth para imprimir.";
        if (!ad.isEnabled()) return "Activa el Bluetooth para imprimir.";
        String addr = pickAddress();
        if (addr == null) {
            act.runOnUiThread(act::choosePrinterDialog);
            return "Elige la impresora para continuar.";
        }
        try {
            if (sock == null || !addr.equalsIgnoreCase(sockAddr) || !sock.isConnected()) {
                closeQuietly();
                ad.cancelDiscovery();
                BluetoothDevice dev = ad.getRemoteDevice(addr);
                BluetoothSocket s;
                try {
                    s = dev.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Exception first) {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                    s.connect();
                }
                sock = s;
                sockAddr = addr;
            }
            OutputStream os = sock.getOutputStream();
            os.write(escpos(bmp));
            os.flush();
            return null;
        } catch (Exception e) {
            return "No se pudo imprimir. Revisa que la impresora tenga papel y esté encendida.";
        }
    }

    void closeQuietly() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        sockAddr = null;
    }

    /** Imagen en blanco y negro como raster ESC/POS (GS v 0), en franjas para impresoras con poco búfer. */
    static byte[] escpos(Bitmap src) {
        Bitmap bmp = src;
        if (src.getWidth() != WIDTH) {
            int h = Math.max(1, Math.round(src.getHeight() * (WIDTH / (float) src.getWidth())));
            bmp = Bitmap.createScaledBitmap(src, WIDTH, h, true);
        }
        int w = bmp.getWidth(), h = bmp.getHeight(), bw = (w + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B); out.write(0x40);             // ESC @  iniciar
        out.write(0x1B); out.write(0x61); out.write(1); // ESC a 1 centrar
        int[] row = new int[w];
        final int BAND = 96;
        for (int y0 = 0; y0 < h; y0 += BAND) {
            int bh = Math.min(BAND, h - y0);
            out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0);
            out.write(bw & 0xFF); out.write((bw >> 8) & 0xFF);
            out.write(bh & 0xFF); out.write((bh >> 8) & 0xFF);
            for (int y = y0; y < y0 + bh; y++) {
                bmp.getPixels(row, 0, w, 0, y, w, 1);
                for (int xb = 0; xb < bw; xb++) {
                    int v = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = xb * 8 + bit;
                        if (x < w) {
                            int c = row[x];
                            int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                            if (Color.alpha(c) > 127 && lum < 128) v |= (0x80 >> bit);
                        }
                    }
                    out.write(v);
                }
            }
        }
        out.write(0x1B); out.write(0x64); out.write(4);  // ESC d 4  avanzar papel
        return out.toByteArray();
    }
}
