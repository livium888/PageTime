package com.pagetime.app.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagetime.app.data.local.ReaderSettings

private val FONT_OPTIONS = listOf(
    "serif" to "Serif",
    "sans" to "Sans",
    "literata" to "Literata",
    "mono" to "Mono"
)

private val LINE_SPACING_OPTIONS = listOf(
    "Tight" to 1.2f,
    "Cozy" to 1.5f,
    "Relaxed" to 1.8f,
    "Airy" to 2.1f
)

private val MARGIN_OPTIONS = listOf(
    "Narrow" to 12f,
    "Comfortable" to 20f,
    "Wide" to 34f
)

private const val SAMPLE_TEXT =
    "The quick brown fox leaps over the lazy dog, while the evening sun throws " +
        "long amber shadows across the quiet street. Reading should feel like " +
        "a real book — settled, comfortable, and entirely yours."

/**
 * The Kindle-style "Aa" panel. Every control applies immediately (no save button)
 * and the preview card at the top shows the result before you close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onApply: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var fontSize by remember(settings.fontSizeSp) { mutableStateOf(settings.fontSizeSp) }
    var lineHeight by remember(settings.lineHeight) { mutableStateOf(settings.lineHeight) }
    var fontFamily by remember(settings.fontFamily) { mutableStateOf(settings.fontFamily) }
    var theme by remember(settings.theme) { mutableStateOf(settings.theme) }
    var margin by remember(settings.marginDp) { mutableStateOf(settings.marginDp) }
    var alignment by remember(settings.alignment) { mutableStateOf(settings.alignment) }
    var brightness by remember(settings.brightness) { mutableStateOf(settings.brightness ?: 1f) }
    var useSystemBrightness by remember(settings.brightness == null) {
        mutableStateOf(settings.brightness == null)
    }

    fun apply() {
        onApply(
            ReaderSettings(
                fontSizeSp = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                theme = theme,
                marginDp = margin,
                alignment = alignment,
                brightness = brightness.takeIf { !useSystemBrightness }
            )
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Text(
                "Changes apply instantly, like a real e-reader.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            AppearancePreview(
                text = SAMPLE_TEXT,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                theme = theme,
                alignment = alignment
            )

            AppearanceSectionLabel("Text size")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepperButton(enabled = fontSize > 12f, decrease = true) {
                    fontSize = (fontSize - 1f).coerceAtLeast(12f)
                    apply()
                }
                Text(
                    "Aa",
                    fontFamily = readerFontFamily(fontFamily),
                    fontSize = fontSize.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${fontSize.toInt()} sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                StepperButton(enabled = fontSize < 32f, decrease = false) {
                    fontSize = (fontSize + 1f).coerceAtMost(32f)
                    apply()
                }
            }
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it },
                onValueChangeFinished = { apply() },
                valueRange = 12f..32f
            )

            AppearanceSectionLabel("Font")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FONT_OPTIONS.forEach { (key, label) ->
                    SelectorPill(
                        selected = fontFamily == key,
                        label = label,
                        onClick = {
                            fontFamily = key
                            apply()
                        }
                    )
                }
            }

            AppearanceSectionLabel("Line spacing")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LINE_SPACING_OPTIONS.forEach { (label, value) ->
                    SelectorPill(
                        selected = lineHeight == value,
                        label = label,
                        onClick = {
                            lineHeight = value
                            apply()
                        }
                    )
                }
            }

            AppearanceSectionLabel("Margins")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MARGIN_OPTIONS.forEach { (label, value) ->
                    SelectorPill(
                        selected = margin == value,
                        label = label,
                        onClick = {
                            margin = value
                            apply()
                        }
                    )
                }
            }

            AppearanceSectionLabel("Alignment")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectorPill(
                    selected = alignment != "justify",
                    label = "Left",
                    onClick = {
                        alignment = "left"
                        apply()
                    }
                )
                SelectorPill(
                    selected = alignment == "justify",
                    label = "Justify",
                    onClick = {
                        alignment = "justify"
                        apply()
                    }
                )
            }

            AppearanceSectionLabel("Theme")
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderPalettes.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ThemeSwatch(selected = theme == p.key, palette = p) {
                            theme = p.key
                            apply()
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(p.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            AppearanceSectionLabel("Brightness")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (useSystemBrightness) "Follow system" else "Brightness — ${(brightness * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = {
                    useSystemBrightness = !useSystemBrightness
                    apply()
                }) {
                    Text(if (useSystemBrightness) "Adjust" else "Use system")
                }
            }
            Slider(
                value = brightness,
                onValueChange = { brightness = it },
                onValueChangeFinished = { apply() },
                valueRange = 0.15f..1f,
                enabled = !useSystemBrightness
            )
        }
    }
}

@Composable
private fun AppearancePreview(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    fontFamily: String,
    theme: String,
    alignment: String
) {
    val palette = paletteFor(theme)
    Surface(
        color = palette.background,
        contentColor = palette.text,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text,
                fontFamily = readerFontFamily(fontFamily),
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeight).sp,
                textAlign = if (alignment == "justify") TextAlign.Justify else TextAlign.Start,
                letterSpacing = (fontSize * 0.015f).sp,
                color = palette.text
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Aa · ${fontSize.toInt()}pt · ${FontWeight.Normal.weight}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary
            )
        }
    }
}

@Composable
private fun AppearanceSectionLabel(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StepperButton(enabled: Boolean, decrease: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            if (decrease) Icons.Filled.Remove else Icons.Filled.Add,
            contentDescription = if (decrease) "Decrease" else "Increase"
        )
    }
}

@Composable
private fun SelectorPill(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun ThemeSwatch(selected: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(palette.background)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}