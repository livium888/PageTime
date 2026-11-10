package com.pagetime.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagetime.app.ui.SectionHeader

/**
 * The prompts PageTime hands to the reader's own AI app, how much of a page a
 * capture carries, and the search model that finds related notes.
 *
 * Nothing here talks to an AI service: there is no provider, key or model to
 * choose. The prompts are the text the reader copies or shares, and are theirs
 * to reword.
 *
 *  - **Prompts** — what the AI app is asked for, per kind of card.
 *  - **Capture size** — how much text a card capture reads.
 *  - **Finding related notes** — the small search model that runs on the phone.
 *  - **Background analysis** — how often the on-device concept map is refreshed.
 *
 * The card composables live in SettingsScreen.kt; only where they appear is
 * decided here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsAndSearchScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val aiSettings by viewModel.aiSettings.collectAsStateWithLifecycle()
    val lumenPrompt by viewModel.lumenPrompt.collectAsStateWithLifecycle()
    val lumenPromptIsCustom by viewModel.lumenPromptIsCustom.collectAsStateWithLifecycle()
    val chapterPrompt by viewModel.chapterPrompt.collectAsStateWithLifecycle()
    val chapterPromptIsCustom by viewModel.chapterPromptIsCustom.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prompts & search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader("Prompts for your AI app")
            CardDensityCard(
                density = viewModel.cardDensity.collectAsStateWithLifecycle().value,
                onSelect = viewModel::setCardDensity,
            )
            CapturePromptCard(
                prompt = lumenPrompt,
                isCustom = lumenPromptIsCustom,
                onSave = viewModel::setLumenPrompt,
                onReset = viewModel::resetLumenPrompt
            )
            ChapterPromptCard(
                prompt = chapterPrompt,
                isCustom = chapterPromptIsCustom,
                onSave = viewModel::setChapterPrompt,
                onReset = viewModel::resetChapterPrompt
            )
            CaptureSizeCard(
                captureChars = viewModel.captureChars.collectAsStateWithLifecycle().value,
                onSelect = viewModel::setCaptureChars,
            )

            SectionHeader("Finding related notes")
            EmbeddingModelSettingsCard(
                status = viewModel.embeddingModelStatus.collectAsStateWithLifecycle().value,
                selfTest = viewModel.embeddingSelfTest.collectAsStateWithLifecycle().value,
                selfTestRunning =
                    viewModel.embeddingSelfTestRunning.collectAsStateWithLifecycle().value,
                pending = viewModel.embeddingPending.collectAsStateWithLifecycle().value,
                indexing = viewModel.embeddingIndexing.collectAsStateWithLifecycle().value,
                onDownload = viewModel::downloadEmbeddingModel,
                onDelete = viewModel::deleteEmbeddingModel,
                onSelfTest = viewModel::runEmbeddingSelfTest,
                onIndexAll = viewModel::indexAllCards,
                onRefreshPending = viewModel::refreshEmbeddingPending,
            )

            SectionHeader("Background analysis")
            AiAnalysisSettingsCard(
                level = aiSettings.analysisLevel,
                onSelect = viewModel::setAiAnalysisLevel
            )
        }
    }
}
