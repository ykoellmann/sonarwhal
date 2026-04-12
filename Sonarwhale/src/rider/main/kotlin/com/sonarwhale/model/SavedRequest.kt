package com.sonarwhale.model

import java.util.UUID

/**
 * A named, executable request that belongs to an EndpointSchema (ApiEndpoint).
 * Multiple SavedRequests can exist per endpoint — e.g. "Happy Path", "Missing Field".
 * Exactly one per endpoint should have isDefault=true; that one is executed by the gutter icon.
 */
data class SavedRequest(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val isDefault: Boolean = false,
    val headers: String = "",                       // JSON array of NameValueRow
    val body: String = "",
    val bodyMode: String = "none",                  // "none" | "form-data" | "raw" | "binary"
    val bodyContentType: String = "application/json",
    val paramValues: Map<String, String> = emptyMap(),
    val paramEnabled: Map<String, Boolean> = emptyMap()  // enabled state per param key
)
