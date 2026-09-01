package com.pagetime.app.data

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LumenModelStoreTest {
    private val directory: File = Files.createTempDirectory("lumen-model-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private class FakeModelDownloader(
        private val bytes: Long,
        private val failWith: Throwable? = null,
        private val onProgressCalls: MutableList<Pair<Long, Long>> = mutableListOf(),
    ) : LumenModelDownloader {
        val progressCalls: List<Pair<Long, Long>> get() = onProgressCalls

        override suspend fun download(
            url: String,
            target: File,
            onProgress: suspend (downloaded: Long, total: Long) -> Unit,
        ): Result<File> {
            failWith?.let { return Result.failure(it) }
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                val buffer = ByteArray(4 * 1024)
                var written = 0L
                while (written < bytes) {
                    val chunk = minOf(buffer.size.toLong(), bytes - written).toInt()
                    output.write(buffer, 0, chunk)
                    written += chunk
                    onProgressCalls += written to bytes
                    onProgress(written, bytes)
                }
            }
            return Result.success(target)
        }
    }

    private fun store(
        expectedBytes: Long,
        downloader: LumenModelDownloader,
        fetcher: suspend (String) -> LumenRemoteModelInfo? = { null },
    ) = LumenModelStore(
        directory = directory,
        downloader = downloader,
        expectedBytes = expectedBytes,
        remoteInfoFetcher = fetcher,
    )

    @Test
    fun `download writes the model file and reports Ready`() =
        runTest {
            // 12 KB target: the fake's 4 KB buffer yields intermediate progress calls.
            val downloader = FakeModelDownloader(bytes = 12 * 1024)
            val modelStore = store(expectedBytes = 12 * 1024, downloader = downloader)

            modelStore.download()

            assertTrue(modelStore.isInstalled())
            assertEquals(12 * 1024L, modelStore.modelFile.length())
            assertEquals(LumenModelStatus.Ready(12 * 1024), modelStore.status.value)
            assertTrue(
                "progress must be reported during the download",
                downloader.progressCalls.any { it.first in 1 until 12 * 1024 },
            )
        }

    @Test
    fun `failure cleans up the partial file and reports Failed`() =
        runTest {
            val modelStore =
                store(
                    expectedBytes = 1_000,
                    downloader =
                        FakeModelDownloader(
                            bytes = 1_000,
                            failWith = IllegalStateException("network dropped"),
                        ),
                )

            modelStore.download()

            assertFalse(modelStore.isInstalled())
            assertFalse(modelStore.modelFile.exists())
            val status = modelStore.status.value
            assertTrue(status is LumenModelStatus.Failed)
            assertTrue((status as LumenModelStatus.Failed).message.contains("network dropped"))
        }

    @Test
    fun `a short download is treated as failed and cleaned up`() =
        runTest {
            val modelStore =
                store(
                    expectedBytes = 1_000,
                    downloader = FakeModelDownloader(bytes = 500),
                )

            modelStore.download()

            assertFalse(modelStore.isInstalled())
            assertFalse(modelStore.modelFile.exists())
            assertTrue(modelStore.status.value is LumenModelStatus.Failed)
        }

    @Test
    fun `delete removes the model and reports NotDownloaded`() =
        runTest {
            val modelStore =
                store(
                    expectedBytes = 1_000,
                    downloader = FakeModelDownloader(bytes = 1_000),
                )
            modelStore.download()
            assertTrue(modelStore.isInstalled())

            modelStore.deleteModel()

            assertFalse(modelStore.modelFile.exists())
            assertEquals(LumenModelStatus.NotDownloaded, modelStore.status.value)
        }

    @Test
    fun `a stale partial file is replaced on retry`() =
        runTest {
            directory.mkdirs()
            File(directory, LumenModelStore.MODEL_FILE_NAME).writeBytes(ByteArray(200))
            val downloader = FakeModelDownloader(bytes = 1_000)
            val modelStore = store(expectedBytes = 1_000, downloader = downloader)

            modelStore.download()

            assertTrue(modelStore.isInstalled())
            assertEquals(1_000L, modelStore.modelFile.length())
        }

    @Test
    fun `download is a no-op when the model is already installed`() =
        runTest {
            directory.mkdirs()
            File(directory, LumenModelStore.MODEL_FILE_NAME).writeBytes(ByteArray(1_000))
            var downloadAttempts = 0
            val downloader =
                object : LumenModelDownloader {
                    override suspend fun download(
                        url: String,
                        target: File,
                        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
                    ): Result<File> {
                        downloadAttempts++
                        return Result.failure(IllegalStateException("should not be called"))
                    }
                }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader)

            modelStore.download()

            assertEquals(0, downloadAttempts)
            assertEquals(LumenModelStatus.Ready(1_000), modelStore.status.value)
        }

    @Test
    fun `checkForUpdate flags a same-size remote change via etag`() =
        runTest {
            var remote = LumenRemoteModelInfo(sizeBytes = 1_100, etag = "\"v1\"")
            val downloader = FakeModelDownloader(bytes = 1_100)
            val fetcher: suspend (String) -> LumenRemoteModelInfo? = { remote }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader, fetcher = fetcher)
            modelStore.download()
            assertTrue(modelStore.isInstalled())

            // Same size, new etag: the file on the server changed in place.
            remote = LumenRemoteModelInfo(sizeBytes = 1_100, etag = "\"v2\"")
            modelStore.checkForUpdate()

            assertEquals(LumenModelStatus.UpdateAvailable(1_100, 1_100), modelStore.status.value)
        }

    @Test
    fun `checkForUpdate flags a remote size change`() =
        runTest {
            var remote = LumenRemoteModelInfo(sizeBytes = 1_100, etag = "\"v1\"")
            val downloader = FakeModelDownloader(bytes = 1_100)
            val fetcher: suspend (String) -> LumenRemoteModelInfo? = { remote }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader, fetcher = fetcher)
            modelStore.download()

            remote = LumenRemoteModelInfo(sizeBytes = 1_200, etag = "\"v1\"")
            modelStore.checkForUpdate()

            assertTrue(modelStore.status.value is LumenModelStatus.UpdateAvailable)
        }

    @Test
    fun `checkForUpdate keeps Ready when remote matches the stored fingerprint`() =
        runTest {
            val downloader = FakeModelDownloader(bytes = 1_000)
            val fetcher: suspend (String) -> LumenRemoteModelInfo? = {
                LumenRemoteModelInfo(sizeBytes = 1_000, etag = "\"same\"")
            }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader, fetcher = fetcher)
            modelStore.download()

            modelStore.checkForUpdate()

            assertEquals(LumenModelStatus.Ready(1_000), modelStore.status.value)
        }

    @Test
    fun `checkForUpdate with unreachable remote keeps the current status`() =
        runTest {
            val modelStore =
                store(
                    expectedBytes = 1_000,
                    downloader = FakeModelDownloader(bytes = 1_000),
                )
            modelStore.download()

            modelStore.checkForUpdate()

            assertEquals(LumenModelStatus.Ready(1_000), modelStore.status.value)
        }

    @Test
    fun `updating replaces the installed model and re-fingerprints it`() =
        runTest {
            directory.mkdirs()
            File(directory, LumenModelStore.MODEL_FILE_NAME).writeBytes(ByteArray(1_000))
            val downloader = FakeModelDownloader(bytes = 1_100)
            val fetcher: suspend (String) -> LumenRemoteModelInfo? = {
                LumenRemoteModelInfo(sizeBytes = 1_100, etag = "\"v2\"")
            }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader, fetcher = fetcher)

            modelStore.download()

            assertTrue(modelStore.isInstalled())
            assertEquals(1_100L, modelStore.modelFile.length())
            assertEquals(LumenModelStatus.Ready(1_100), modelStore.status.value)
            // After the update the fingerprint matches the server again.
            modelStore.checkForUpdate()
            assertEquals(LumenModelStatus.Ready(1_100), modelStore.status.value)
        }

    @Test
    fun `a failed update leaves the previously installed model intact`() =
        runTest {
            directory.mkdirs()
            File(directory, LumenModelStore.MODEL_FILE_NAME).writeBytes(ByteArray(1_000))
            val downloader =
                FakeModelDownloader(
                    bytes = 1_100,
                    failWith = IllegalStateException("network dropped"),
                )
            val fetcher: suspend (String) -> LumenRemoteModelInfo? = {
                LumenRemoteModelInfo(sizeBytes = 1_100, etag = "\"v2\"")
            }
            val modelStore = store(expectedBytes = 1_000, downloader = downloader, fetcher = fetcher)

            modelStore.download()

            assertTrue(modelStore.status.value is LumenModelStatus.Failed)
            assertEquals(1_000L, modelStore.modelFile.length())
            assertFalse(File(directory, "${LumenModelStore.MODEL_FILE_NAME}.part").exists())
        }

    @Test
    fun `downloading fraction clamps between zero and one`() {
        assertEquals(0.5f, LumenModelStatus.Downloading(50, 100).fraction)
        assertEquals(1f, LumenModelStatus.Downloading(200, 100).fraction)
        assertEquals(0f, LumenModelStatus.Downloading(10, 0).fraction)
    }
}
