package com.qcenaman.remoteai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qcenaman.remoteai.net.Approval

/** Heads-up notification for an approval that arrives while the app isn't on screen. */
object ApprovalNotifier {
    private const val CHANNEL = "approvals"
    private const val ID = 2

    fun show(ctx: Context, a: Approval) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Approvals", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        val n = b.setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (a.waiting) "Approve now? The PC is waiting (${a.id})" else "Approve on your PC? (${a.id})")
            .setContentText(a.what)
            .setStyle(Notification.BigTextStyle().bigText(a.what))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
