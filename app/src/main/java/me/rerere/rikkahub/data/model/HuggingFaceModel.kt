package me.rerere.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * HuggingFace's `gated` field is the JSON boolean `false` for a public repo, or one of the
 * strings "auto" / "manual" for a gated one (verified against the live `/api/models/{repo}`
 * response on 2026-08-03). Decodes to a plain Boolean: false means public, true means
 * access-gated regardless of which string HF used for the gate kind.
 */
object HfGatedSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HfGated", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return true
        return primitive.booleanOrNull != false
    }
}

/**
 * One file inside a HuggingFace repo, as returned by `/api/models/{repo}`. [size] is only
 * populated when the request passed `?blobs=true`; without it HuggingFace omits the field
 * entirely.
 */
@Serializable
data class HfSibling(
    val rfilename: String,
    val size: Long? = null,
)

/**
 * A single row from `/api/models?search=...`: enough to show a repo in a picker list without
 * paying for its (potentially large) file listing.
 */
@Serializable
data class HfModelSearchResult(
    val id: String,
    val downloads: Long = 0,
)

/**
 * Full repo detail from `/api/models/{repo}?blobs=true`. [gated] and [private] are the
 * authoritative signal for whether a download is even possible unauthenticated.
 */
@Serializable
data class HfModelDetail(
    val id: String,
    @Serializable(with = HfGatedSerializer::class) val gated: Boolean = false,
    val private: Boolean = false,
    val siblings: List<HfSibling> = emptyList(),
)
