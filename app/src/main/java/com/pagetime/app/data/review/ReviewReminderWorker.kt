package com.pagetime.app.data.review

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pagetime.app.MainActivity
import com.pagetime.app.PageTimeApp
import com.pagetime.app.R
import java.util.concurrent.TimeUnit

/**
 * Deciding, a few times a day, whether a review sitting is worth mentioning.
 *
 * WHY THE APP HAS NEVER HAD THIS
 *
 * Quantum Country's retention comes substantially from the email nudge. A
 * spaced repetition system whose reader has to remember to open it is not a
 * spaced repetition system; it is a pile of cards. This app had no
 * notification code at all — no channel, no permission, no scheduler.
 *
 * WHY IT DOES NOT SIMPLY FIRE WHEN A CARD IS DUE
 *
 * That produces a dribble of two-card pings, and a notification usually not
 * worth opening teaches the reader to dismiss it. [ReviewReminder] holds the
 * decision, ported from Orbit: wait while waiting is cheap, speak when the
 * delay starts costing real memories, and stop asking after six unanswered
 * attempts.
 *
 * WHAT RUNS HERE RATHER THAN THERE
 *
 * Only the parts that need Android: reading the cards, checking the reader's
 * preferences and permission, and posting. Every judgement is in the pure
 * object, where it is tested.
 */
class ReviewReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PageTimeApp ?: return Result.success()
        val container = app.container
        val settings = container.settingsRepository

        // Silence beats correctness here: every early return is a decision not
        // to interrupt someone, and the cost of getting that wrong once is the
        // reader turning notifications off forever.
        if (!settings.reviewReminders()) return Result.success()

        val now = System.currentTimeMillis()
        if (now < settings.remindersSnoozedUntil()) return Result.success()
        if (!ReviewReminder.mayRemindAgain(
                remindersSent = settings.unansweredReminders(),
                millisSinceLastReminder = now - settings.lastReminderAt(),
            )
        ) {
            return Result.success()
        }
        if (!canPost()) return Result.success()

        val cards = collectCards(container, now)
        val verdict = ReviewReminder.decide(cards, now)
        if (!verdict.shouldNotify) return Result.success()

        val dueCount = ReviewReminder.dueAt(cards, now).size
        notify(dueCount)
        settings.recordReminderSent(now)
        return Result.success()
    }

    /**
     * Every card that could be reviewed, from both tables.
     *
     * Interval is taken as the gap the scheduler actually chose — due minus
     * the moment it was scheduled — rather than parsed out of the FSRS blob.
     * The blob is the library's format and reading it here would tie the
     * reminder to a dependency's internals for one number that is already
     * implied by two columns.
     */
    private suspend fun collectCards(
        container: com.pagetime.app.data.AppContainer,
        now: Long,
    ): List<DueCard> {
        val horizon = now + 60L * ReviewReminder.DAY_MILLIS
        val learning = runCatching {
            container.database.learningCardDao().dueCards(horizon, LOOKAHEAD_LIMIT)
        }.getOrDefault(emptyList()).map { card ->
            DueCard(
                dueAtMillis = card.dueAt ?: now,
                intervalMillis = ((card.dueAt ?: now) - card.updatedAt).coerceAtLeast(0L),
            )
        }
        val lumen = runCatching {
            container.database.lumenCardDao().dueCards(horizon, LOOKAHEAD_LIMIT)
        }.getOrDefault(emptyList()).map { card ->
            DueCard(
                dueAtMillis = card.dueAt ?: now,
                intervalMillis = ((card.dueAt ?: now) - card.updatedAt).coerceAtLeast(0L),
            )
        }
        return learning + lumen
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notify(dueCount: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Review reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When a review sitting is worth having."
            }
        )

        // Tapping it has to go somewhere. A reminder that opens nothing is a
        // reminder the reader dismisses, and this codebase has shipped
        // built-but-unreachable five times already.
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_REVIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_review)
            .setContentTitle("Time for a review")
            .setContentText(
                if (dueCount == 1) {
                    "One card is ready."
                } else {
                    "$dueCount cards are ready."
                }
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        // Posting is wrapped because the permission can be revoked between the
        // check above and here, and a reminder is never worth a crash.
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Marks a launch that came from a reminder, so it opens the session. */
        const val ACTION_OPEN_REVIEW = "com.pagetime.app.OPEN_REVIEW"

        private const val CHANNEL_ID = "review_reminders"
        private const val NOTIFICATION_ID = 4201
        private const val WORK_NAME = "review-reminders"

        /**
         * How many cards to look at.
         *
         * The decision only needs enough to tell a full sitting from a thin
         * one and to see whether a fuller one is coming; loading a reader's
         * entire deck to answer that would be work done every few hours
         * forever.
         */
        private const val LOOKAHEAD_LIMIT = 200

        /**
         * Checked every six hours, which is not the same as notifying every
         * six hours.
         *
         * Almost every run decides to say nothing. The frequency is only about
         * how promptly the app notices that the moment has arrived; the
         * backoff ladder and the decay model decide whether it speaks.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, so an app relaunch does not reset the period and
                // quietly stop the work from ever running on a phone that is
                // opened often.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
