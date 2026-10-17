package online.internet25.comercio;

import android.content.Context;

final class BuildConfigLite {
    static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static long versionCode(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return 0; }
    }
}
