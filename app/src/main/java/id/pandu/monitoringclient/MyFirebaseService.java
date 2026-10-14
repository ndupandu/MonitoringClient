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

import java.util.Map;

/** Menerima FCM saat APK terbuka (saat tertutup, sistem Android menampilkan notifikasinya sendiri). */
public class MyFirebaseService extends FirebaseMessagingService {
    public static final String CH_LOUD = "tiket_baru";   // harus sama dengan FCM_CHANNEL_LOUD di server
    public static final String CH_INFO = "tiket_info";   // harus sama dengan FCM_CHANNEL_INFO di server
    public static final String PREF = "fcm";

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
    }
}
