package id.pandu.monitoringclient;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;


/** Pembungkus dashboard web dalam WebView: unggah foto, kamera, lokasi, unduhan, dan notifikasi FCM. */
public class MainActivity extends AppCompatActivity {

    /** True selama aplikasi tampil di layar (dibaca MyFirebaseService agar sapaan suara tidak dobel dengan halaman web). */
    static volatile boolean visible = false;

    private WebView web;
    private String baseUrl;
    private String baseHost;
    private volatile String curHost = "";           // host halaman yang sedang tampil (dibaca dari thread jembatan JS)

    private ValueCallback<Uri[]> filePathCb;
    private PermissionRequest pendingWebPerm;
    private GeolocationPermissions.Callback pendingGeoCb;
    private String pendingGeoOrigin;
    private String[] pendingDownload;
    private boolean pendingLocStart = false;       // menunggu jawaban izin lokasi untuk memulai berbagi lokasi latar belakang
    private boolean locAsked = false;               // izin lokasi hanya ditanyakan sekali per proses (halaman memanggil start() tiap dimuat)
    private boolean healed = false;                 // pengaman 404: maksimal satu kali pulang ke beranda per kejadian

    private ActivityResultLauncher<Intent> chooserLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        baseUrl = BuildConfig.BASE_URL;
        baseHost = Uri.parse(baseUrl).getHost();
        MyFirebaseService.createChannels(this);

        chooserLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (filePathCb == null) return;
            Uri[] res = WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData());
            filePathCb.onReceiveValue(res);
            filePathCb = null;
        });

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            if (res.containsKey(Manifest.permission.POST_NOTIFICATIONS)) askBatteryExemption();
            if (pendingWebPerm != null && res.containsKey(Manifest.permission.CAMERA)) {
                if (Boolean.TRUE.equals(res.get(Manifest.permission.CAMERA))) {
                    pendingWebPerm.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingWebPerm.deny();
                }
                pendingWebPerm = null;
            }
            if (pendingGeoCb != null && res.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
                boolean ok = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
                pendingGeoCb.invoke(pendingGeoOrigin, ok, false);
                pendingGeoCb = null;
            }
            if (pendingLocStart && res.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)) {
                pendingLocStart = false;
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) beginLocationSharing();
            }
            if (pendingDownload != null && res.containsKey(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                String[] d = pendingDownload;
                pendingDownload = null;
                if (Boolean.TRUE.equals(res.get(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
                    enqueueDownload(d[0], d[1], d[2], d[3]);
                } else {
                    toast("Izin penyimpanan ditolak, unduhan dibatalkan.");
                }
            }
        });

        web = new WebView(this);
        setContentView(web);
        setupWebView();

        // Izin notifikasi (Android 13+) dan token FCM
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            permLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});   // lanjut ke izin latar belakang setelah dijawab
        } else {
            askBatteryExemption();
        }
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t ->
                    getSharedPreferences(MyFirebaseService.PREF, MODE_PRIVATE).edit().putString("token", t).apply());
        } catch (Throwable ignored) { /* Firebase belum disiapkan (google-services.json belum ada) */ }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            String p = pathFrom(getIntent());
            web.loadUrl(baseUrl + (p != null ? p : "/"));
        }
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // sirine tiket baru saat aplikasi terbuka
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new FcmBridge(), "AndroidFCM");
        web.addJavascriptInterface(new LocBridge(), "AndroidLoc");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                if (("http".equals(sch) || "https".equals(sch)) && sameHost(u.getHost())) return false;
                openExternal(u);                          // Google Maps, WhatsApp, tel:, dll
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                Uri u = Uri.parse(url);
                curHost = u.getHost() == null ? "" : u.getHost();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                String path = Uri.parse(url).getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) healed = false;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }

            /** Halaman utama menjawab 404 (jalur basi/salah, mis. /login/ setelah login): pulang ke beranda, sekali saja. */
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (!r.isForMainFrame() || e.getStatusCode() != 404 || healed) return;
                String path = r.getUrl().getPath();
                if (!sameHost(r.getUrl().getHost()) || path == null || path.isEmpty() || "/".equals(path)) return;
                healed = true;
                v.post(() -> v.loadUrl(baseUrl + "/"));
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    chooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCb = null;
                    toast("Tidak bisa membuka pemilih file.");
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (!sameHost(req.getOrigin().getHost())) { req.deny(); return; }
                    boolean cam = false;
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) cam = true;
                    }
                    if (!cam) { req.deny(); return; }
                    if (has(Manifest.permission.CAMERA)) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingWebPerm = req;
                        permLauncher.launch(new String[]{Manifest.permission.CAMERA});
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (!sameHost(Uri.parse(origin).getHost())) { cb.invoke(origin, false, false); return; }
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false);
                } else {
                    pendingGeoCb = cb;
                    pendingGeoOrigin = origin;
                    permLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
                }
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!sameHost(Uri.parse(url).getHost())) { openExternal(Uri.parse(url)); return; }
            if (Build.VERSION.SDK_INT <= 28 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                pendingDownload = new String[]{url, ua, cd, mime};
                permLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
                return;
            }
            enqueueDownload(url, ua, cd, mime);
        });
    }

    /** Minta izin berjalan di latar belakang (kecualikan dari hemat baterai) sekali saja, agar notifikasi tidak ditahan HP. */
    private void askBatteryExemption() {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (sp.getBoolean("battery_asked", false) || pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        sp.edit().putBoolean("battery_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Izinkan berjalan di latar belakang")
                .setMessage("Supaya notifikasi tiket tetap masuk saat aplikasi ditutup dan HP terkunci, izinkan aplikasi ini berjalan di latar belakang tanpa pembatasan baterai.")
                .setPositiveButton("Izinkan", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("Nanti", null)
                .show();
    }

    private void enqueueDownload(String url, String ua, String cd, String mime) {
        try {
            String fn = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) rq.addRequestHeader("Cookie", cookie);
            if (ua != null) rq.addRequestHeader("User-Agent", ua);
            rq.setTitle(fn);
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
            toast("Mengunduh " + fn);
        } catch (Exception e) {
            toast("Unduhan gagal.");
        }
    }

    private void showOffline() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:48px 24px;background:#0f172a;color:#e2e8f0'>"
                + "<h2>Tidak bisa terhubung</h2><p>Periksa internet atau alamat server, lalu coba lagi.</p>"
                + "<button style='padding:12px 28px;font-size:16px;border:0;border-radius:12px' "
                + "onclick=\"location.href='" + baseUrl + "'\">Coba lagi</button></body></html>";
        web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
    }

    /** Mulai berbagi lokasi latar belakang (thread utama). Dipanggil halaman setiap dimuat, jadi harus aman diulang. */
    private void startLocationSharing() {
        if (LocationService.running) return;
        if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            beginLocationSharing();
        } else if (!locAsked) {
            locAsked = true;
            pendingLocStart = true;
            permLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void beginLocationSharing() {
        try {
            LocationService.start(this);              // dimulai saat aplikasi tampil, jadi diizinkan Android
        } catch (Exception e) {
            toast("Berbagi lokasi di latar belakang tidak bisa dimulai.");
            return;
        }
        askBackgroundLocation();
    }

    /** Android 10+: izin "Izinkan sepanjang waktu" diminta terpisah, sekali saja, supaya lokasi tetap terkirim bila layanan dimulai ulang sistem. */
    private void askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return;
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (sp.getBoolean("bgloc_asked", false)) return;
        sp.edit().putBoolean("bgloc_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Lokasi di latar belakang")
                .setMessage("Agar NOC tetap melihat posisi Anda saat aplikasi ditutup atau layar mati, pilih \"Izinkan sepanjang waktu\" untuk lokasi. "
                        + "Selama berbagi lokasi aktif, ada notifikasi tetap \"Lokasi dibagikan ke NOC\". Berhenti dengan keluar dari akun.")
                .setPositiveButton("Lanjut", (d, w) -> {
                    try {
                        permLauncher.launch(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION});
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Nanti", null)
                .show();
    }

    private boolean sameHost(String host) {
        return host != null && baseHost != null && host.equalsIgnoreCase(baseHost);
    }

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) { /* tidak ada aplikasi pembuka */ }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    /** Jalur halaman dari ketukan notifikasi (extra "url" dikirim server). Hanya jalur di server sendiri yang diterima. */
    private String pathFrom(Intent i) {
        String p = i == null ? null : i.getStringExtra("url");
        return (p != null && p.startsWith("/") && !p.startsWith("//")) ? p : null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String p = pathFrom(intent);
        if (p != null) web.loadUrl(baseUrl + p);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();            // sesi login tidak hilang saat aplikasi dimatikan
    }

    @Override
    protected void onDestroy() {
        if (filePathCb != null) { filePathCb.onReceiveValue(null); filePathCb = null; }
        web.destroy();
        super.onDestroy();
    }

    /** Dipanggil halaman dashboard untuk akun teknisi: berbagi lokasi ke NOC lewat layanan foreground (tetap jalan saat aplikasi ditutup). */
    private class LocBridge {
        @JavascriptInterface
        public void start(String origin) {              // alamat dari halaman diabaikan: layanan selalu memakai BuildConfig.BASE_URL
            if (!sameHost(curHost)) return;
            runOnUiThread(MainActivity.this::startLocationSharing);
        }

        @JavascriptInterface
        public void stop() {
            if (!sameHost(curHost)) return;
            LocationService.stopSharing(MainActivity.this);
        }

        @JavascriptInterface
        public boolean running() {
            return LocationService.running;
        }
    }

    /** Dibaca halaman e-tiket (JavaScript) untuk mendaftarkan HP ini ke server. Hanya menjawab untuk halaman dashboard sendiri. */
    private class FcmBridge {
        @JavascriptInterface
        public String getToken() {
            if (!sameHost(curHost)) return "";
            return getSharedPreferences(MyFirebaseService.PREF, MODE_PRIVATE).getString("token", "");
        }

        /** Saklar "Sapaan suara tiket baru" di halaman e-tiket; dipakai juga saat aplikasi tertutup. */
        @JavascriptInterface
        public void setVoice(boolean on) {
            if (!sameHost(curHost)) return;
            getSharedPreferences(MyFirebaseService.PREF, MODE_PRIVATE).edit().putBoolean("voice", on).apply();
        }

        @JavascriptInterface
        public boolean enabled() {
            return NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled();
        }

        @JavascriptInterface
        public void openSettings() {
            if (!sameHost(curHost)) return;
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        }
    }
}
