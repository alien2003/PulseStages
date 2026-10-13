package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The parameters hash written with every estimate, so that a night can be recognised as computed with different
 * settings and recomputed later.
 */
object ParamsHasher {
    /** Canonical JSON: every field (defaults and nulls included), declaration order, no whitespace. */
    private val canonical: Json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    /** The canonical JSON form of [params] that is hashed. */
    fun canonicalJson(params: AlgoParams): String = canonical.encodeToString(AlgoParams.serializer(), params)

    /**
     * SHA-256 (lower-case hex) of `canonicalJson(params) + "|model=" + (modelFingerprint ?: "none") +
     * "|personal=" + (personalFingerprint ?: "none") + "|" + extra`.
     *
     * @param modelFingerprint fingerprint of the hybrid model bundle, if one is used.
     * @param personalFingerprint fingerprint of the personal model, if one is applied.
     * @param extra output options that change the written data (e.g. "awakeAsLight=true").
     */
    fun hash(
        params: AlgoParams,
        modelFingerprint: String? = null,
        personalFingerprint: String? = null,
        extra: String = "",
    ): String {
        val text =
            canonicalJson(params) + "|model=" + (modelFingerprint ?: NONE) + "|personal=" + (personalFingerprint ?: NONE) + "|" + extra
        return sha256Hex(text)
    }

    /** SHA-256 of the UTF-8 bytes of [text] as lower-case hex. */
    fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val NONE = "none"
}
