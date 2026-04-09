// Instally Android SDK
// Track clicks, installs, and revenue from every link.
// https://instally.io

package io.instally.sdk

/**
 * Result of an install attribution request.
 *
 * @property matched Whether the install was attributed to a tracking link.
 * @property attributionId Unique ID for this attribution (null if not matched).
 * @property confidence Attribution confidence score (0.0 to 1.0).
 * @property method The method used for attribution (e.g. "referrer", "fingerprint", "cached", "error").
 * @property clickId The click ID from the tracking link (null if not matched).
 */
data class AttributionResult(
    val matched: Boolean,
    val attributionId: String?,
    val confidence: Double,
    val method: String,
    val clickId: String?
)
