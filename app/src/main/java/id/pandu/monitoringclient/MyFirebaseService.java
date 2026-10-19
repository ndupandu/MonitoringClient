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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Menerima FCM (data-only) baik saat APK terbuka maupun tertutup, menampilkan notifikasi, lalu mengucapkan isi "speak"
 * (mis. "Halo budi, ada tiket baru nih, ayo segera ambil") lewat TextToSpeech bila server menyertakannya.
 */
public class MyFirebaseService extends FirebaseMessagingService {
    public static final String CH_LOUD = "tiket_baru";   // harus sama dengan FCM_CHANNEL_LOUD di server
    public static final String CH_INFO = "tiket_info";   // harus sama dengan FCM_CHANNEL_INFO di server
    public static final String PREF = "fcm";
    /** true = suara lewat volume ALARM (tetap bunyi walau volume notifikasi kecil); false = volume notifikasi. */
    private static final boolean SPEAK_AS_ALARM = false;

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

        String speak = d.get("speak");
        if (speak != null && !speak.trim().isEmpty()) speak(speak.trim());
    }

    /**
     * Ucapkan teks dengan TextToSpeech. onMessageReceived berjalan di thread latar (batas ~20 detik), jadi thread ini
     * ditahan sampai ucapan selesai agar proses tidak dimatikan sistem di tengah kalimat.
     */
    private void speak(final String text) {
        final Handler main = new Handler(Looper.getMainLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final TextToSpeech[] holder = new TextToSpeech[1];
        final String uid = "tiket" + System.nanoTime();

        main.post(() -> {
            try {
                holder[0] = new TextToSpeech(getApplicationContext(), status -> {
                    TextToSpeech t = holder[0];
                    if (t == null || status != TextToSpeech.SUCCESS) { done.countDown(); return; }
                    try {
                        t.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(SPEAK_AS_ALARM ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        int r = t.setLanguage(new Locale("id", "ID"));
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            t.setLanguage(Locale.getDefault());     // suara Indonesia belum terpasang
                        }
                        t.setSpeechRate(0.95f);
                        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { }
                            @Override public void onDone(String id) { if (uid.equals(id)) done.countDown(); }
                            @Override public void onError(String id) { if (uid.equals(id)) done.countDown(); }
                        });
                        // jeda 1,5 detik supaya nada notifikasi selesai dulu, baru suara bicara
                        t.playSilentUtterance(1500, TextToSpeech.QUEUE_FLUSH, "jeda");
                        Bundle p = new Bundle();
                        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
                        if (t.speak(text, TextToSpeech.QUEUE_ADD, p, uid) != TextToSpeech.SUCCESS) done.countDown();
                    } catch (Exception e) {
                        done.countDown();
                    }
                });
            } catch (Exception e) {
                done.countDown();
            }
        });

        try {
            done.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            TextToSpeech t = holder[0];
            if (t != null) { try { t.stop(); t.shutdown(); } catch (Exception ignored) { } }
        });
    }
}
