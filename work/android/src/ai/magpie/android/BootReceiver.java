package ai.magpie.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Non-root autostart: Android starts the foreground service at boot. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean auto = Core.prefs(context).getBoolean("autostart", false)
                || Core.autoFlag(context).exists();
        if (!auto) return;
        if (Core.prefs(context).getBoolean("shouldRun", true)) {
            MagpieService.fire(context, true);
        }
    }
}
