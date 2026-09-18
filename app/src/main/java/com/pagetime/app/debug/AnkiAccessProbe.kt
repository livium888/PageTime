package com.pagetime.app.debug

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * TEMPORARY DIAGNOSTIC — not a shipped feature.
 *
 * Android's own docs say Android/data is off-limits to every app, even one
 * holding MANAGE_EXTERNAL_STORAGE. This checks that directly on a real
 * device instead of trusting the docs: after the user grants a folder via
 * the system picker, can PageTime actually walk down into a Play Store
 * install of AnkiDroid's data folder and see its collection file?
 *
 * Delete this file and its one call site in SettingsScreen once the answer
 * is known, whichever way it comes out.
 */
object AnkiAccessProbe {

    private val PATH = listOf("Android", "data", "com.ichi2.anki", "files", "AnkiDroid", "collection.anki2")

    fun walk(context: Context, treeUri: Uri): String {
        val resolver = context.contentResolver
        var documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val log = StringBuilder("Granted root: $treeUri\n\n")
        for (name in PATH) {
            when (val result = findChild(resolver, treeUri, documentId, name)) {
                is StepResult.Found -> {
                    log.append("✓ Found \"$name\"\n")
                    documentId = result.documentId
                }
                is StepResult.NotFound -> {
                    log.append("✗ \"$name\" was not among the children listed under the current folder.\n")
                    return log.toString()
                }
                is StepResult.Error -> {
                    log.append("✗ Listing the current folder threw: ${result.message}\n")
                    return log.toString()
                }
            }
        }
        log.append("\nSuccess: reached collection.anki2 at document id $documentId")
        return log.toString()
    }

    private sealed class StepResult {
        data class Found(val documentId: String) : StepResult()
        object NotFound : StepResult()
        data class Error(val message: String) : StepResult()
    }

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        name: String
    ): StepResult {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        return try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var found: StepResult = StepResult.NotFound
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameCol) == name) {
                        found = StepResult.Found(cursor.getString(idCol))
                        break
                    }
                }
                found
            } ?: StepResult.Error("query returned null (no provider for this tree)")
        } catch (e: Exception) {
            StepResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
