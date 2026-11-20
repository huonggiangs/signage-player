package vn.anio.signage

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent

class App : Application() {
    companion object {
        /** Mở lại app sau 2 giây rồi thoát tiến trình hiện tại. */
        fun restart(ctx: android.content.Context) {
            try {
                val pi = PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                )
                (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager)
                    .set(AlarmManager.RTC, System.currentTimeMillis() + 2000, pi)
            } catch (_: Exception) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Logger.log("CRASH ${e}")
                val pi = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                )
                (getSystemService(ALARM_SERVICE) as AlarmManager)
                    .set(AlarmManager.RTC, System.currentTimeMillis() + 3000, pi)
            } catch (_: Exception) {
            }
            if (old != null) old.uncaughtException(t, e) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }
}
