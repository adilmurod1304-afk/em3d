package uz.namdpi.childguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telefon qayta yoqilganda himoyani avtomatik ishga tushiradi.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.isGuardEnabled(context)
        ) {
            MonitorService.start(context)
        }
    }
}
