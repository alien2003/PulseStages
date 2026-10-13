package io.github.alien2003.pulsestages.platform

import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Writes text files that can be handed to other apps through the share sheet. */
interface ShareableFileWriter {
    /**
     * Writes [text] to a private cache file named [fileName] and returns a `content://` uri (as a string) that can be
     * granted to the receiving app. Throws [DocumentAccessException] on failure.
     */
    suspend fun write(
        fileName: String,
        text: String,
    ): String
}

/**
 * [ShareableFileWriter] backed by the app's FileProvider (authority `<applicationId>.fileprovider`, directory
 * `cache/shared/`, see `res/xml/file_paths.xml`). The file lives in the cache, so Android may delete it later.
 */
class FileProviderShareableFileWriter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ShareableFileWriter {
        override suspend fun write(
            fileName: String,
            text: String,
        ): String =
            withContext(io) {
                try {
                    val directory = File(context.cacheDir, SHARED_DIRECTORY)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
                    val file = File(directory, fileName)
                    file.writeText(text)
                    FileProvider.getUriForFile(context, authority(context), file).toString()
                } catch (e: IOException) {
                    throw DocumentAccessException("cannot write $fileName", e)
                } catch (e: IllegalArgumentException) {
                    throw DocumentAccessException("cannot share $fileName", e)
                }
            }

        /** Constants. */
        companion object {
            /** Sub-directory of the cache that the FileProvider exposes. */
            const val SHARED_DIRECTORY: String = "shared"

            /** The FileProvider authority of this app. */
            fun authority(context: Context): String = context.packageName + ".fileprovider"
        }
    }
