package ai.magpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps magpie running. In root mode the daemon is a detached root process and
 * this service is only a handle plus a watchdog; otherwise it owns the process.
 * All the slow work (su, copying the binary) happens off the main thread.
 */
public class MagpieService extends Service {

    public static final String ACTION_START = "ai.magpie.android.START";
    public static final String ACTION_STOP = "ai.magpie.android.STOP";

    private static final String CHANNEL = "magpie";
    private static final int NOTIF_ID = 3430;

    private Handler handler;
    private Runnable watchdog;
    private volatile boolean working;

    /** A watchdog that puts magpie back when something killed it. */
    private final class Watchdog implements Runnable {
        @Override
        public void run() {
            boolean want = Core.prefs(MagpieService.this).getBoolean("shouldRun", false);
            if (!want) return;
            long startedAt = Core.prefs(MagpieService.this).getLong("startedAt", 0);
            long age = System.currentTimeMillis() - startedAt;
            // magpie takes a moment to unpack and bind on a phone; never fight it
            if (!Core.running() && age > 60000 && !working
                    && !Core.prefs(MagpieService.this).getBoolean("updating", false)) {
                String detail = Core.probeDetail(MagpieService.this);
                Core.appendLog(MagpieService.this, "[watchdog] down after " + (age / 1000)
                        + "s, restarting\n" + "[probe] " + detail);
                android.util.Log.w("magpie", "watchdog probe: " + detail);
                startWorker("watchdog");
            }
            handler.postDelayed(this, 15000);
        }
    }

    public static void fire(Context c, boolean run) {
        Intent i = new Intent(c, MagpieService.class);
        i.setAction(run ? ACTION_START : ACTION_STOP);
        try {
            if (run && Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        watchdog = new Watchdog();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "magpie", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("magpie")
                .setContentText("网关 " + Core.gatewayUrl(this) + " · 正在后台守护")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void foreground() {
        Notification n = notification();
        // the specialUse type only exists from Android 14; older systems take the plain call
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startWorker(final String why) {
        if (working) return;
        working = true;
        Core.prefs(this).edit().putLong("startedAt", System.currentTimeMillis()).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!Core.running()) {
                        Core.appendLog(MagpieService.this, "[service] starting (" + why + ")");
                        int mode = Core.start(MagpieService.this, Core.useRoot(MagpieService.this));
                        Core.prefs(MagpieService.this).edit().putInt("lastMode", mode).apply();
                    }
                } catch (Throwable t) {
                    Core.appendLog(MagpieService.this, "[service] start failed: " + t);
                } finally {
                    working = false;
                }
            }
        }, "magpie-start").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent == null: 系统重启了服务（START_STICKY）。用户按过停止就别复活。
        if (intent == null && !Core.prefs(this).getBoolean("shouldRun", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Core.prefs(this).edit().putBoolean("shouldRun", false).apply();
            if (handler != null && watchdog != null) handler.removeCallbacks(watchdog);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Core.stop(MagpieService.this, true);
                }
            }, "magpie-stop").start();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        Core.prefs(this).edit().putBoolean("shouldRun", true).apply();
        foreground();
        startWorker("run");
        if (handler != null && watchdog != null) {
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, 15000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null && watchdog != null) handler.removeCallbacks(watchdog);
        super.onDestroy();
    }
}
