package com.pagetime.app.ui

import android.app.Application
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * A screen that writes `vm: SomeViewModel = viewModel()` makes Android build
 * it by calling a constructor that takes only the Application. A view model
 * with any other required parameter has no such constructor (a Kotlin default
 * value does not create one), so the screen crashes the moment it opens: the
 * Review screen did exactly that.
 *
 * This reads every screen's source, finds each view model created with the
 * default factory, and checks the class really has that constructor.
 */
class DefaultViewModelFactoryTest {

    private val pattern = Regex("""\b(?:val|var)?\s*\w+\s*:\s*([A-Za-z0-9_.]+ViewModel)\s*=\s*viewModel\(\s*\)""")

    @Test
    fun `every view model made with the default factory can be constructed by it`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val failures = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            pattern.findAll(text).forEach { match ->
                val simple = match.groupValues[1].substringAfterLast('.')
                val candidates = listOfNotNull(
                    pkg?.let { "$it.$simple" },
                    root.walkTopDown().firstOrNull { it.name == "$simple.kt" }?.let { found ->
                        val p = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
                            .find(found.readText())?.groupValues?.get(1)
                        p?.let { "$it.$simple" }
                    },
                )
                val cls = candidates.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
                    ?: return@forEach
                val ok = runCatching { cls.getConstructor(Application::class.java) }.isSuccess
                if (!ok) failures += "${file.name}: $simple has no constructor taking only an Application"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
