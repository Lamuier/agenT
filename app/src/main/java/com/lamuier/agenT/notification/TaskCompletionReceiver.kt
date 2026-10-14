package com.lamuier.agenT.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lamuier.agenT.AgenTApplication
import com.lamuier.agenT.data.CursorRepository
import com.lamuier.agenT.model.AgenTasks
import java.security.MessageDigest
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Local acknowledgement only: task history and remote tasks are never changed. */
class TaskCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM) return
        val accountId = intent.getIntExtra(EXTRA_ACCOUNT, -1)
        val revision = intent.getLongExtra(EXTRA_REVISION, -1)
        val times = intent.getBundleExtra(EXTRA_COMPLETIONS) ?: return
        if (accountId <= 0 || revision < 0) return
        val completions = times.keySet().associateWith { times.getLong(it) }
        val pendingResult = goAsync()
        val app = context.applicationContext as AgenTApplication
        app.applicationScope.launch {
            try {
                runCatching {
                    CursorRepository(app).acknowledgeTaskCompletions(accountId, revision, completions)
                    AgenTNotificationCoordinator.get(app).refreshCopyFromCache()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_CONFIRM = "com.lamuier.agenT.action.CONFIRM_TASK_COMPLETIONS"
        private const val EXTRA_ACCOUNT = "account_id"
        private const val EXTRA_REVISION = "account_revision"
        private const val EXTRA_COMPLETIONS = "completion_times"

        internal fun pendingIntent(context: Context, tasks: AgenTasks, revision: Long, nowMillis: Long): PendingIntent? {
            val completions = TaskActivityModel.recentCompletions(tasks, nowMillis)
            if (completions.isEmpty()) return null
            // Intent extras do not distinguish PendingIntents. Give each displayed batch its own identity.
            val identity = "${tasks.accountId}|$revision|${JSONObject(completions.toSortedMap())}"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val intent = Intent(context, TaskCompletionReceiver::class.java).apply {
                action = ACTION_CONFIRM
                data = "agentt://task-completions/$digest".toUri()
                putExtra(EXTRA_ACCOUNT, tasks.accountId)
                putExtra(EXTRA_REVISION, revision)
                putExtra(EXTRA_COMPLETIONS, Bundle().apply { completions.forEach { (id, time) -> putLong(id, time) } })
            }
            return PendingIntent.getBroadcast(context, 4204, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
