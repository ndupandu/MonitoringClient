package id.pandu.monitoringclient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Berbagi lokasi teknisi ke NOC di latar belakang (layanan foreground bertipe lokasi).
 *
 * Alur: halaman e-tiket (akun teknisi) memanggil AndroidLoc.start() -> MainActivity menyalakan layanan ini. Selama berjalan,
 * posisi dikirim tiap ~15 detik ke POST {BASE_URL}/api/teknisi/lokasi dengan cookie sesi login yang sama dengan WebView
 * (server tidak diubah; endpoint dan format sama dengan mode browser). Berhenti saat teknisi keluar (AndroidLoc.stop()),
 * saat tombol "Berhenti" di notifikasi diketuk, atau bila server menolak sesi (401/403).
 *
 * Android mewajibkan notifikasi tetap selama layanan lokasi berjalan; itu juga tanda bagi teknisi bahwa lokasinya dibagikan.
 */
public class LocationService extends Service implements LocationListener {
    static final String ACTION_STOP = "id.pandu.monitoringclient.LOC_STOP";
    private static final String CH = "lokasi";
    private static final int NID = 4711;
    private static final long INTERVAL_MS = 15000;      // permintaan ke sistem
    private static final long MIN_SEND_MS = 12000;      // jarak minimal antar kiriman ke server
    private static final String PREF = "loc";           // "on" = berbagi lokasi aktif (dipulihkan bila layanan dimulai ulang sistem)

    /** True selama layanan berjalan (dibaca MainActivity agar tidak memulai berulang tiap halaman dimuat). */
    static volatile boolean running = false;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LocationManager lm;
    private long lastSent = 0;
    private boolean requested = false;

    // ------------------------------------------------------------------ dipanggil MainActivity

    static void start(Context c) {
        c.getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("on", true).apply();
        ContextCompat.startForegroundService(c, new Intent(c, LocationService.class));
    }

    /** Hentikan layanan dan beri tahu server (posisi dihapus dari peta NOC). Aman dipanggil dari thread mana pun. */
    static void stopSharing(Context c) {
        final Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF, MODE_PRIVATE);
        boolean was = sp.getBoolean("on", false);
        sp.edit().putBoolean("on", false).apply();
        final String cookie = was ? cookieNow() : null;          // dibaca SEKARANG, sebelum logout menghapus sesi
        app.stopService(new Intent(app, LocationService.class));
        running = false;
        if (cookie != null) {
            new Thread(() -> post(cookie, "{\"stop\":true}")).start();
        }
    }

    // ------------------------------------------------------------------ siklus layanan

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSharing(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android mewajibkan startForeground() segera setelah startForegroundService(), juga bila layanan ternyata tidak perlu
        // menyala; jadi selalu dipanggil dulu, baru diputuskan boleh lanjut atau berhenti.
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NID, n);
            }
        } catch (Exception e) {
            // mis. Android melarang memulai layanan foreground dari latar belakang: berhenti dengan tertib
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        // Dimulai ulang sistem (intent null) tapi teknisi sudah berhenti / keluar, atau izin lokasi dicabut -> jangan menyala sendiri.
        if (!getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("on", false) || !hasLocationPermission()) {
            running = false;
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        requestUpdates();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) { }
        net.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ lokasi

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void requestUpdates() {
        if (requested) return;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        boolean any = false;
        // GPS bila izin akurat ada; jaringan sebagai cadangan (dalam ruangan / GPS belum dapat sinyal).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper());
                any = true;
            } catch (Exception ignored) { }
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper());
            any = true;
        } catch (Exception ignored) { }
        requested = any;
        // kirim posisi terakhir yang diketahui supaya NOC langsung melihat teknisi tanpa menunggu fix baru
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < 120000) onLocationChanged(last);
        } catch (Exception ignored) { }
    }

    @Override
    public void onLocationChanged(Location loc) {
        long now = System.currentTimeMillis();
        if (now - lastSent < MIN_SEND_MS) return;
        lastSent = now;
        final String cookie = cookieNow();             // dibaca di thread utama (callback memakai Looper utama)
        if (cookie == null) return;                    // belum/ tidak ada sesi login
        final String json;
        try {
            JSONObject o = new JSONObject();
            o.put("lat", loc.getLatitude());
            o.put("lng", loc.getLongitude());
            if (loc.hasAccuracy()) o.put("acc", loc.getAccuracy());
            if (loc.hasSpeed()) o.put("speed", loc.getSpeed());
            json = o.toString();
        } catch (Exception e) {
            return;
        }
        try {
            net.execute(() -> {
                int code = post(cookie, json);
                if (code == 401 || code == 403) {      // sesi habis / bukan akun teknisi -> berhenti, jangan terus mencoba
                    getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("on", false).apply();
                    running = false;
                    stopSelf();
                }
            });
        } catch (Exception ignored) { /* executor sudah ditutup */ }
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    // ------------------------------------------------------------------ jaringan

    /** Cookie sesi WebView untuk dashboard; null bila belum login. */
    @Nullable
    private static String cookieNow() {
        try {
            String c = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL);
            return (c == null || c.trim().isEmpty()) ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    /** -> kode HTTP, atau -1 bila gagal tersambung. */
    private static int post(String cookie, String json) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(BuildConfig.BASE_URL + "/api/teknisi/lokasi").openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(10000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("X-Requested-With", "dash");
            h.setRequestProperty("Cookie", cookie);
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = h.getOutputStream()) {
                os.write(b);
            }
            int code = h.getResponseCode();
            try { h.getInputStream().close(); } catch (Exception ignored) { /* badan tidak dipakai */ }
            return code;
        } catch (Exception e) {
            return -1;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    // ------------------------------------------------------------------ notifikasi

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "Berbagi lokasi", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Tanda bahwa lokasi Anda sedang dibagikan ke NOC selama bekerja");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LocationService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_notif)
                .setContentTitle("Lokasi dibagikan ke NOC")
                .setContentText("Aktif selama Anda masuk sebagai teknisi. Keluar dari akun untuk berhenti.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .addAction(0, "Berhenti", stop)
                .build();
    }
}
