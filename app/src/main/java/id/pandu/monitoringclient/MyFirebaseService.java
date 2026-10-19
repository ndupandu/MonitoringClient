package id.pandu.monitoringclient;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.lang.reflect.Field;
import java.util.Map;

/** Menerima FCM. Pesan "notification" saat aplikasi tertutup ditampilkan sistem sendiri; pesan data-only (berisi "speak") selalu lewat sini. */
public class MyFirebaseService extends FirebaseMessagingService {
    public static final String CH_LOUD = "tiket_baru";   // harus sama dengan FCM_CHANNEL_LOUD di server
    public static final String CH_INFO = "tiket_info";   // harus sama dengan FCM_CHANNEL_INFO di server
    public static final String PREF = "fcm";
    /** Versi saluran sapaan. Harus sama dengan FCM_NAMED_VER di server. Naikkan (v2, v3, ...) bila file mp3 di res/raw diganti,
     *  karena nada sebuah saluran tidak bisa diubah setelah dibuat. */
    public static final String NAMED_VER = "v2";

    /** Panggil dari MainActivity.onCreate() DAN dari sini. Aman dipanggil berulang. */
    public static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();

        NotificationChannel loud = new NotificationChannel(CH_LOUD, "Tiket baru & chat CS", NotificationManager.IMPORTANCE_HIGH);
        loud.setDescription("Tiket baru, tiket tersedia lagi, dan detail dari CS");
        loud.setSound(snd, aa);
        loud.enableVibration(true);
        loud.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
        loud.enableLights(true);
        loud.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(loud);

        NotificationChannel info = new NotificationChannel(CH_INFO, "Info tiket", NotificationManager.IMPORTANCE_DEFAULT);
        info.setDescription("Pembaruan tiket biasa");
        nm.createNotificationChannel(info);

        createNamedChannels(c, nm, aa);
    }

    /**
     * Satu saluran per file res/raw/<nama>.mp3 (mis. aping.mp3 -> saluran "tiket_baru_aping_v1" bernada sapaan teknisi itu).
     * Server memilih saluran sesuai nama penerima (data "chan"), lalu sistem Android sendiri memutar mp3-nya, sehingga
     * tetap bunyi walau aplikasi tertutup. Tidak ada file di res/raw = tidak ada saluran tambahan.
     */
    private static void createNamedChannels(Context c, NotificationManager nm, AudioAttributes aa) {
        // Jalur ALARM: memakai volume alarm (bisa dibuat sangat keras) dan tetap bunyi walau mode senyap.
        AudioAttributes alarm = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        try {
            // buang saluran sapaan versi lama (mis. _v1) agar daftar saluran di Setelan HP tidak menumpuk
            String keep = "_" + NAMED_VER;
            for (NotificationChannel old : nm.getNotificationChannels()) {
                String id = old.getId();
                if (id.startsWith(CH_LOUD + "_") && !id.endsWith(keep)) nm.deleteNotificationChannel(id);
            }
        } catch (Exception ignored) { }
        try {
            Class<?> raw = Class.forName(R.class.getName() + "$raw");
            for (Field f : raw.getFields()) {
                String name = f.getName();
                int resId = f.getInt(null);
                Uri snd = Uri.parse("android.resource://" + c.getPackageName() + "/" + resId);
                NotificationChannel ch = new NotificationChannel(CH_LOUD + "_" + name + "_" + NAMED_VER,
                        "Sapaan tiket: " + name, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Tiket baru untuk " + name + " (nada sapaan suara)");
                ch.setSound(snd, alarm);
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
                ch.enableLights(true);
                ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        } catch (ClassNotFoundException e) {
            // belum ada file res/raw: tidak ada saluran sapaan, notifikasi memakai saluran biasa
        } catch (Exception ignored) { }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // Token disimpan; halaman e-tiket (JS subFcm) mengirimnya ke server atas nama pengguna yang sedang login.
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("token", token).apply();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        String title = d.get("title"), body = d.get("body");
        if (title == null && msg.getNotification() != null) {
            title = msg.getNotification().getTitle();
            body = msg.getNotification().getBody();
        }
        if (title == null) return;
        boolean loud = "1".equals(d.get("loud"));
        boolean urgent = "1".equals(d.get("urgent"));
        String tag = d.get("tag") != null ? d.get("tag") : "tiket";
        createChannels(this);

        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("url", d.get("url") != null ? d.get("url") : "/tiket");
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, tag.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, loud ? CH_LOUD : CH_INFO)
                .setSmallIcon(R.drawable.ic_stat_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(loud ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(urgent ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE);
        try {
            // tag yang sama dengan notifikasi buatan sistem -> tidak dobel
            NotificationManagerCompat.from(this).notify(tag, 0, b.build());
        } catch (SecurityException ignored) { /* izin POST_NOTIFICATIONS belum diberikan */ }

        // Sapaan suara ("Halo <nama>, ada tiket baru nih ...") dari server. Saat aplikasi sedang tampil, halaman web
        // yang mengucapkannya sendiri (supaya tidak dobel), jadi di sini dilewati.
        String speak = d.get("speak");
        if (speak != null && !speak.trim().isEmpty() && loud && !MainActivity.visible
                && getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("voice", true)) {
            VoiceAnnouncer.sayAndWait(this, speak.trim(), 1300, 15000);       // jeda: bunyi notifikasi selesai dulu
        }
    }
}