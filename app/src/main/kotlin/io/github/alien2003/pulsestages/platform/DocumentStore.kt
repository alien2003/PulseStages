package io.github.alien2003.pulsestages.platform

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** A document could not be read or written (missing, not accessible, too large, ...). */
class DocumentAccessException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Reads and writes text documents chosen by the user through the Storage Access Framework (`CreateDocument` /
 * `OpenDocument`). Uris are passed as strings so that ViewModels stay free of Android types.
 */
interface DocumentStore {
    /**
     * Reads the UTF-8 text of [uri]. Throws [DocumentAccessException] when it cannot be read or is larger than
     * [maxBytes].
     */
    suspend fun readText(
        uri: String,
        maxBytes: Int,
    ): String

    /** Replaces the content of [uri] with the UTF-8 [text]. Throws [DocumentAccessException] on failure. */
    suspend fun writeText(
        uri: String,
        text: String,
    )
}

/** [DocumentStore] over the application's ContentResolver. */
class ContentResolverDocumentStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : DocumentStore {
        override suspend fun readText(
            uri: String,
            maxBytes: Int,
        ): String =
            withContext(io) {
                val bytes =
                    guarded(uri) {
                        context.contentResolver.openInputStream(uri.toUri())?.use { it.readNBytesCompat(maxBytes + 1) }
                    } ?: throw DocumentAccessException("no input stream for $uri")
                if (bytes.size > maxBytes) throw DocumentAccessException("document is larger than $maxBytes bytes")
                bytes.decodeToString()
            }

        override suspend fun writeText(
            uri: String,
            text: String,
        ) {
            withContext(io) {
                // "wt" truncates an existing document instead of overwriting only its first bytes.
                guarded(uri) {
                    context.contentResolver.openOutputStream(uri.toUri(), "wt")?.use { it.write(text.encodeToByteArray()) }
                } ?: throw DocumentAccessException("no output stream for $uri")
            }
        }

        /** Runs [block], turning the failures of the content resolver into [DocumentAccessException]. */
        private inline fun <T> guarded(
            uri: String,
            block: () -> T,
        ): T =
            try {
                block()
            } catch (e: SecurityException) {
                throw DocumentAccessException("no access to $uri", e)
            } catch (e: IllegalArgumentException) {
                throw DocumentAccessException("invalid document uri $uri", e)
            } catch (e: IOException) {
                throw DocumentAccessException("cannot access $uri", e)
            }
    }

/** Reads at most [limit] bytes (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val BUFFER_SIZE = 8 * 1024
