package com.inweb.app.ddns

/**
 * Supported Dynamic-DNS providers.
 *
 * INWEB picks one via Settings and periodically pushes the device's current
 * public IP to that provider so a friendly hostname always resolves to your
 * phone.
 *
 * Add a new provider in three steps:
 *   1. Add an enum entry here with metadata.
 *   2. Implement a matching updater in [DdnsClient.push].
 *   3. Update the strings for the auth-field label.
 */
enum class DdnsProvider(
    val id: String,
    val displayName: String,
    val tagline: String,
    val domainSuffix: String,
    /** Label shown next to the token/password field in Settings. */
    val credentialLabel: String,
    /** True if this provider needs a *username* in addition to the token. */
    val needsUsername: Boolean = false
) {
    DUCKDNS(
        id = "duckdns", displayName = "DuckDNS",
        tagline = "Free forever · token-based · super simple",
        domainSuffix = ".duckdns.org",
        credentialLabel = "DuckDNS token"
    ),

    NOIP(
        id = "noip", displayName = "No-IP",
        tagline = "Free tier · username + password",
        domainSuffix = ".ddns.net",
        credentialLabel = "No-IP password",
        needsUsername = true
    ),

    CLOUDFLARE(
        id = "cloudflare", displayName = "Cloudflare",
        tagline = "Advanced · API token · full DNS control",
        domainSuffix = "",         // user brings their own domain
        credentialLabel = "Cloudflare API token",
        needsUsername = true       // Zone ID goes in the "username" slot
    ),

    INAYA(
        id = "inaya", displayName = "INAYA DNS",
        tagline = "INWEB-hosted · one-tap · free for Muslim devs",
        domainSuffix = ".inayadns.app",
        credentialLabel = "INAYA API key"
    );

    companion object {
        fun fromId(id: String?): DdnsProvider =
            entries.firstOrNull { it.id == id } ?: DUCKDNS
    }
}
