package id.pandu.monitoringclient;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Mengucapkan sapaan tiket baru lewat TextToSpeech bawaan HP (bahasa Indonesia), juga saat aplikasi tertutup.
 * Dipanggil dari MyFirebaseService. Semua pekerjaan di thread utama; WakeLock dan pengaman waktu memastikan
 * proses tidak dimatikan sebelum kalimat selesai dan semua sumber daya dilepas sesudahnya.
 */
public final class VoiceAnnouncer {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final String TAG = "VoiceAnnouncer";
    private static final long SAFETY_MS = 20000;       // batas total: setelah ini semuanya dilepas paksa

    private static TextToSpeech tts;
    private static PowerManager.WakeLock wl;
    private static AudioManager am;
    private static AudioFocusRequest afr;
    private static int gen = 0;                        // nomor sesi: pengaman lama tidak mematikan sesi yang lebih baru

    private VoiceAnnouncer() {}

    /** Ucapkan `text` setelah `delayMs` (memberi ruang bunyi notifikasi selesai dulu). Aman dipanggil dari thread mana pun. */
    public static void say(final Context ctx, final String text, final long delayMs) {
        final Context app = ctx.getApplicationContext();
        H.post(() -> start(app, text, delayMs));
    }

    private static void start(final Context app, final String text, final long delayMs) {
        release();
        final int my = ++gen;
        Log.d(TAG, "start: \"" + text + "\"");
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monitoringclient:voice");
                wl.acquire(SAFETY_MS);
            }
        } catch (Exception ignored) { /* tanpa wakelock tetap dicoba */ }

        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(app, status -> {
            if (my != gen) return;
            final TextToSpeech t = holder[0];
            Log.d(TAG, "TTS init status=" + status);
            if (status != TextToSpeech.SUCCESS || t == null) { release(); return; }
            int lang = t.setLanguage(new Locale("id", "ID"));      // bila data bahasa belum ada, memakai bahasa HP
            Log.d(TAG, "setLanguage(id-ID)=" + lang + " (-1/-2 = suara Indonesia belum ada)");
            t.setAudioAttributes(attrs());
            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { Log.d(TAG, "mulai bicara"); }
                @Override public void onDone(String utteranceId) { H.post(() -> { if (my == gen) release(); }); }
                @Override public void onError(String utteranceId) { Log.w(TAG, "TTS error"); H.post(() -> { if (my == gen) release(); }); }
            });
            H.postDelayed(() -> {
                if (my != gen) return;
                requestFocus(app);
                Bundle p = new Bundle();
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
                int r = t.speak(text, TextToSpeech.QUEUE_FLUSH, p, "tiket" + my);
                Log.d(TAG, "speak() result=" + r + " (0 = berhasil diantrekan)");
                if (r != TextToSpeech.SUCCESS) release();
            }, Math.max(0, delayMs));
        });
        tts = holder[0];
        H.postDelayed(() -> { if (my == gen) release(); }, SAFETY_MS);
    }

    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)               // tetap terdengar walau mode senyap / volume notifikasi 0
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    @SuppressWarnings("deprecation")
    private static void requestFocus(Context app) {
        try {
            am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs()).build();
                am.requestAudioFocus(afr);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private static void release() {
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); }
        } catch (Exception ignored) { }
        tts = null;
        try {
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 26) { if (afr != null) am.abandonAudioFocusRequest(afr); }
                else am.abandonAudioFocus(null);
            }
        } catch (Exception ignored) { }
        afr = null;
        am = null;
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception ignored) { }
        wl = null;
    }
}
