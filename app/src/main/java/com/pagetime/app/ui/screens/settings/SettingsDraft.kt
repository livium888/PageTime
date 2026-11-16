package com.pagetime.app.ui.screens.settings

import com.pagetime.app.data.learning.CardDensity

/**
 * The Settings page as the reader is editing it, before anything is applied.
 *
 * ONE SAVE FOR THE WHOLE PAGE
 *
 * Every switch and value on the page edits this draft. Nothing is written until
 * the reader presses Save changes at the foot of the page, and before it is
 * written they are shown each change as "what it was → what it will be". A
 * setting cannot change because a thumb brushed it, and there is one place to
 * look to see what is about to happen.
 *
 * Pure, so what counts as a change and how it is worded can be tested.
 */
data class SettingsDraft(
    val lockOn: Boolean,
    val targetSeconds: Long,
    val otherAppsCount: Boolean,
    val otherAppsCapSeconds: Long,
    /** Minutes of browsing per minute read; only used while the lock is off. */
    val readingRate: Double,
    val reviewReminders: Boolean,
    val cardDensity: CardDensity,
    val suggestCards: Boolean,
    val suggestEveryWords: Int,
    val slipBoxHelp: Boolean,
    /** Use of blocked apps after the day's reading, in seconds; 0 = until the day resets. */
    val appAllowanceSeconds: Long = 0,
    /** The hour (0-23) the lock's day starts at. */
    val resetHour: Int = 4,
    /** Seconds of reading an answered flashcard counts as; 0 = none. */
    val cardCreditSeconds: Long = 15,
    /** Opening the app goes straight to the book being read. */
    val openOnBook: Boolean = true,
    /** The reader shows what is left to the day's target beside the clock. */
    val showCountdown: Boolean = true,
) {
    /** One line of the confirmation: a setting, what it was, what it will be. */
    data class Change(
        val setting: String,
        val from: String,
        val to: String,
        /** What follows from the change, when that is not obvious from the values. */
        val consequence: String? = null,
    )

    companion object {
        const val DAILY_LOCK = "Daily reading lock"
        const val DAILY_TARGET = "Daily reading target"

        fun onOff(value: Boolean) = if (value) "On" else "Off"

        fun minutes(seconds: Long): String {
            val total = seconds / 60
            val hours = total / 60
            val rest = total % 60
            return when {
                hours == 0L -> "$total min"
                rest == 0L -> "$hours hr"
                else -> "$hours hr $rest min"
            }
        }

        /** "Until the day resets" for no limit, otherwise the time allowed. */
        fun allowance(seconds: Long) = if (seconds <= 0L) "Until the day resets" else minutes(seconds)

        fun hour(hour: Int) = com.pagetime.app.domain.LockDay.resetLabel(hour)

        fun cardCredit(seconds: Long) = if (seconds <= 0L) "Nothing" else "$seconds sec of reading"

        fun rate(value: Double) = "×" + "%.1f".format(java.util.Locale.US, value)

        fun words(n: Int) = "Every " + "%,d".format(java.util.Locale.US, n) + " words"

        /** Everything that differs, in the order the page shows it. */
        fun changes(saved: SettingsDraft, draft: SettingsDraft): List<Change> = buildList {
            if (saved.lockOn != draft.lockOn) {
                add(
                    Change(
                        DAILY_LOCK, onOff(saved.lockOn), onOff(draft.lockOn),
                        if (draft.lockOn) {
                            "Until you have read ${minutes(draft.targetSeconds)} each day, only your allowed " +
                                "apps will open. Turning it off again takes 24 hours."
                        } else {
                            "It switches off 24 hours from now. Until then the lock still applies."
                        },
                    )
                )
            }
            if (saved.targetSeconds != draft.targetSeconds) {
                add(Change(DAILY_TARGET, minutes(saved.targetSeconds), minutes(draft.targetSeconds)))
            }
            if (saved.appAllowanceSeconds != draft.appAllowanceSeconds) {
                add(
                    Change(
                        "After the target, apps open for",
                        allowance(saved.appAllowanceSeconds), allowance(draft.appAllowanceSeconds),
                        if (draft.appAllowanceSeconds > 0L) {
                            "Once that time is used, blocked apps close again until the day resets at ${hour(draft.resetHour)}."
                        } else null,
                    )
                )
            }
            if (saved.resetHour != draft.resetHour) {
                add(
                    Change(
                        "Day resets at", hour(saved.resetHour), hour(draft.resetHour),
                        "Reading and app time start again from zero at that hour each day.",
                    )
                )
            }
            if (saved.otherAppsCount != draft.otherAppsCount) {
                add(
                    Change(
                        "Count reading in other apps", onOff(saved.otherAppsCount), onOff(draft.otherAppsCount),
                        if (draft.otherAppsCount) {
                            "Time in the apps you choose counts at half rate, up to the daily limit."
                        } else null,
                    )
                )
            }
            if (saved.otherAppsCapSeconds != draft.otherAppsCapSeconds) {
                add(
                    Change(
                        "Other apps, most per day",
                        minutes(saved.otherAppsCapSeconds), minutes(draft.otherAppsCapSeconds),
                    )
                )
            }
            if (saved.readingRate != draft.readingRate) {
                add(Change("Browsing earned per minute read", rate(saved.readingRate), rate(draft.readingRate)))
            }
            if (saved.openOnBook != draft.openOnBook) {
                add(Change("Open the app on my book", onOff(saved.openOnBook), onOff(draft.openOnBook)))
            }
            if (saved.showCountdown != draft.showCountdown) {
                add(
                    Change(
                        "Show time left to the target while reading",
                        onOff(saved.showCountdown), onOff(draft.showCountdown),
                    )
                )
            }
            if (saved.reviewReminders != draft.reviewReminders) {
                add(Change("Review reminders", onOff(saved.reviewReminders), onOff(draft.reviewReminders)))
            }
            if (saved.cardCreditSeconds != draft.cardCreditSeconds) {
                add(
                    Change(
                        "Each card answered counts as",
                        cardCredit(saved.cardCreditSeconds), cardCredit(draft.cardCreditSeconds),
                    )
                )
            }
            if (saved.cardDensity != draft.cardDensity) {
                add(Change("How many cards", saved.cardDensity.label, draft.cardDensity.label))
            }
            if (saved.suggestCards != draft.suggestCards) {
                add(Change("Suggest cards while reading", onOff(saved.suggestCards), onOff(draft.suggestCards)))
            }
            if (saved.suggestEveryWords != draft.suggestEveryWords) {
                add(Change("Suggest cards", words(saved.suggestEveryWords), words(draft.suggestEveryWords)))
            }
            if (saved.slipBoxHelp != draft.slipBoxHelp) {
                add(Change("Explain slip-box actions", onOff(saved.slipBoxHelp), onOff(draft.slipBoxHelp)))
            }
        }
    }
}
