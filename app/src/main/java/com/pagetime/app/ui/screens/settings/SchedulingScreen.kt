package com.pagetime.app.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import com.pagetime.app.PageTimeApp
import com.pagetime.app.data.review.RescheduleRepository
import com.pagetime.app.data.review.SchedulingPolicy
import com.pagetime.app.data.review.Steps
import com.pagetime.app.ui.AppCard
import com.pagetime.app.ui.AppPrimaryButton
import com.pagetime.app.ui.AppSecondaryButton
import com.pagetime.app.ui.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the reschedule button is doing, and what it last did. */
sealed interface RescheduleState {
    data object Idle : RescheduleState
    data object Running : RescheduleState
    data class Done(val result: RescheduleRepository.Result) : RescheduleState
}

class SchedulingViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PageTimeApp).container
    private val settings = container.settingsRepository

    val policy = settings.schedulingPolicy
        .stateIn(viewModelScope, SharingStarted.Eagerly, SchedulingPolicy())

    private val _reschedule = MutableStateFlow<RescheduleState>(RescheduleState.Idle)
    val reschedule = _reschedule.asStateFlow()

    /**
     * Applies an edit to the policy.
     *
     * Written through rather than kept in the view model and saved on the way
     * out, because the scheduler reads the stored value on the next card
     * answered: a setting that only takes effect when the reader remembers to go
     * back is a setting that appears not to work.
     */
    fun update(edit: (SchedulingPolicy) -> SchedulingPolicy) {
        viewModelScope.launch {
            runCatching { settings.setSchedulingPolicy(edit(policy.value)) }
        }
    }

    fun reschedule() {
        if (_reschedule.value is RescheduleState.Running) return
        _reschedule.value = RescheduleState.Running
        viewModelScope.launch {
            _reschedule.value = RescheduleState.Done(
                runCatching { container.rescheduleRepository.rescheduleAll() }
                    .getOrElse { RescheduleRepository.Result(0, 0, 0) }
            )
        }
    }

    fun clearReschedule() {
        _reschedule.value = RescheduleState.Idle
    }
}

/**
 * Scheduling settings, in Anki's own vocabulary.
 *
 * WHY THE WORDS ARE ANKI'S
 *
 * Because they are words the reader may already know, and inventing a second
 * set for the same four ideas would make an Anki user learn the app twice.
 * Where Anki hides an option once FSRS is on — Graduating interval, Easy
 * interval, Starting ease — this screen does not offer it either, for the same
 * reason: FSRS derives those from each card's own stability, so a fixed number
 * here would look like a control and be ignored.
 *
 * WHERE THIS DIFFERS FROM ANKI'S DEFAULTS
 *
 * One ten-minute learning step rather than Anki's `1m 10m`. That single change
 * is what stops a brand-new card answered Good from being shown again in ten
 * minutes: with one step, Good is the last step, so the card graduates and FSRS
 * gives it days. Again and Hard still return it in minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulingScreen(
    onBack: () -> Unit,
    vm: SchedulingViewModel = viewModel(),
) {
    val policy by vm.policy.collectAsStateWithLifecycle()
    val reschedule by vm.reschedule.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduling") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SameDayCard(policy = policy, onToggle = vm::update)

            SectionHeader("New cards")
            AppCard {
                StepsField(
                    label = "Learning steps",
                    steps = policy.learningSteps,
                    fallback = SchedulingPolicy.DEFAULT_LEARNING_STEPS,
                    onSteps = { steps -> vm.update { it.copy(learningSteps = steps) } },
                    supporting = "How long a new card waits between attempts before it " +
                        "graduates. Blank means it graduates on its first answer.",
                )
            }

            SectionHeader("Cards you fail")
            AppCard {
                StepsField(
                    label = "Relearning steps",
                    steps = policy.relearningSteps,
                    fallback = SchedulingPolicy.DEFAULT_RELEARNING_STEPS,
                    onSteps = { steps -> vm.update { it.copy(relearningSteps = steps) } },
                    supporting = "The same, for a card you press Again on.",
                )
                DaysField(
                    label = "Minimum interval",
                    value = policy.minimumIntervalDays,
                    range = SchedulingPolicy.MIN_MINIMUM_INTERVAL_DAYS..
                        SchedulingPolicy.MAX_MINIMUM_INTERVAL_DAYS,
                    onValue = { days -> vm.update { it.copy(minimumIntervalDays = days) } },
                    supporting = "The shortest a card may wait once it has finished " +
                        "learning. Anki's default is 1, which is also the shortest " +
                        "interval FSRS will ever give a card — so 1 changes nothing, " +
                        "and this only does anything from 2 upwards.",
                )
            }

            SectionHeader("FSRS")
            AppCard {
                Text(
                    "Desired retention",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${(policy.desiredRetention * 100).toInt()}% likely to be remembered " +
                        "when a card comes up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = policy.desiredRetention.toFloat(),
                    onValueChange = { value ->
                        vm.update { it.copy(desiredRetention = value.toDouble()) }
                    },
                    valueRange = SchedulingPolicy.MIN_RETENTION.toFloat()..
                        SchedulingPolicy.MAX_RETENTION.toFloat(),
                    steps = 26,
                )
                Text(
                    "Higher means shorter intervals and more reviews per day, and it " +
                        "rises steeply: past about 93% the workload climbs faster than " +
                        "the memory does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The FSRS weights themselves are the library's published defaults " +
                        "and are not editable. Anki tunes them to your own review " +
                        "history; that needs a training run this app cannot do, and a " +
                        "number guessed by hand would schedule worse than the defaults.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Advanced")
            AppCard {
                DaysField(
                    label = "Maximum interval",
                    value = policy.maximumIntervalDays,
                    range = SchedulingPolicy.MIN_MAXIMUM_INTERVAL_DAYS..
                        SchedulingPolicy.MAX_MAXIMUM_INTERVAL_DAYS,
                    onValue = { days -> vm.update { it.copy(maximumIntervalDays = days) } },
                    supporting = "The longest a card may ever wait. 36500 days is a " +
                        "century, which is Anki's default and effectively no limit.",
                )
                SwitchRow(
                    title = "Fuzz intervals",
                    subtitle = "Nudges each interval by a few percent so that the cards " +
                        "learned on one day do not all come back on the same day. The " +
                        "numbers on the rating buttons already include the nudge, so " +
                        "they are never off by a day.",
                    checked = policy.fuzzingEnabled,
                    onCheckedChange = { on -> vm.update { it.copy(fuzzingEnabled = on) } },
                )
            }

            SectionHeader("Existing cards")
            AppCard {
                Text(
                    "Changing a schedule does not move cards that were already " +
                        "scheduled — Anki's manual says the same of its own settings. " +
                        "This re-derives their due dates from each card's own memory " +
                        "state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val state = reschedule) {
                    RescheduleState.Idle -> AppPrimaryButton(
                        text = "Move existing cards to this schedule",
                        onClick = vm::reschedule,
                    )

                    RescheduleState.Running -> AppSecondaryButton(
                        text = "Moving…",
                        onClick = {},
                        enabled = false,
                    )

                    is RescheduleState.Done -> {
                        val result = state.result
                        Text(
                            when {
                                result.total == 0 ->
                                    "There are no cards with a schedule yet."
                                else ->
                                    "Moved ${result.moved}. " +
                                        "${result.alreadyRight} were already right. " +
                                        "${result.leftLearning} are still in a learning " +
                                        "step and were left alone — they will be " +
                                        "scheduled properly when you next answer them."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        AppSecondaryButton(text = "Done", onClick = vm::clearReschedule)
                    }
                }
                Text(
                    "A card in a learning step keeps the step it is in. Moving those to " +
                        "a day-scale interval would throw away the repeat they were " +
                        "deliberately given.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The whole point of the screen, in one switch.
 *
 * Written as a switch over the two step lists rather than as a flag of its own,
 * because a flag could then disagree with the fields above it and there would be
 * two answers to one question. The fields are the truth; this is a shortcut to
 * one particular setting of them.
 */
@Composable
private fun SameDayCard(policy: SchedulingPolicy, onToggle: ((SchedulingPolicy) -> SchedulingPolicy) -> Unit) {
    AppCard {
        SwitchRow(
            title = "Never show a card again the same day",
            subtitle = if (policy.graduatesImmediately) {
                "On: no card can come back sooner than a day, because there are no " +
                    "same-day steps left. Good on a new card now gives it days."
            } else {
                "Off: a card you press Again on comes back in " +
                    "${Steps.format(policy.relearningSteps).ifBlank { "a day" }}, today."
            },
            checked = policy.graduatesImmediately,
            onCheckedChange = { on ->
                onToggle {
                    if (on) {
                        it.copy(learningSteps = emptyList(), relearningSteps = emptyList())
                    } else {
                        it.copy(
                            learningSteps = SchedulingPolicy.DEFAULT_LEARNING_STEPS,
                            relearningSteps = SchedulingPolicy.DEFAULT_RELEARNING_STEPS,
                        )
                    }
                }
            },
        )
    }
}

/**
 * A field for one of the step lists, in Anki's syntax.
 *
 * The text being typed is held here rather than round-tripped through settings,
 * so half-typed input like `1` stays on screen and is simply not saved. Clearing
 * the local copy on focus loss then snaps the field back to the canonical form
 * (`60m` becomes `1h`), so the reader can always see what was actually stored.
 */
@Composable
private fun StepsField(
    label: String,
    steps: List<java.time.Duration>,
    fallback: List<java.time.Duration>,
    onSteps: (List<java.time.Duration>) -> Unit,
    supporting: String,
) {
    var typed by remember { mutableStateOf<String?>(null) }
    val canvas = typed ?: Steps.format(steps)
    val parsed = Steps.parse(canvas)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = canvas,
            onValueChange = { text ->
                typed = text
                Steps.parse(text)?.let(onSteps)
            },
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null,
            supportingText = {
                Text(
                    if (parsed == null) {
                        "Steps look like 10m, 1h or 2d, in ascending order. " +
                            "Leave blank for none."
                    } else {
                        supporting
                    }
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) typed = null },
        )
        if (parsed == null) {
            AppSecondaryButton(
                text = "Reset to ${Steps.format(fallback)}",
                onClick = {
                    typed = null
                    onSteps(fallback)
                },
            )
        }
    }
}

/**
 * A whole number of days.
 *
 * Typed rather than dragged. A day count is a number the reader has in mind, not
 * a position on a track, and a slider over a range of a hundred makes "thirty"
 * almost impossible to hit.
 */
@Composable
private fun DaysField(
    label: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    supporting: String,
) {
    var typed by remember { mutableStateOf<String?>(null) }
    val canvas = typed ?: value.toString()
    val entered = canvas.toIntOrNull()
    val valid = entered != null && entered in range

    OutlinedTextField(
        value = canvas,
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(5)
            typed = digits
            digits.toIntOrNull()?.let { if (it in range) onValue(it) }
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = {
            Text(if (valid) supporting else "Between ${range.first} and ${range.last} days.")
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) typed = null },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
