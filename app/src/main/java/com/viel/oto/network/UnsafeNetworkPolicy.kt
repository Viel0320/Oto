package com.viel.oto.network

import com.viel.oto.shared.settings.AppSettings

/**
 * Centralizes user-controlled insecure transport decisions.
 *
 * Keeps cleartext HTTP and insecure TLS bypass rules in one pure policy so WebDAV, ABS, and
 * playback preflight code cannot drift into provider-specific defaults.
 */
object UnsafeNetworkPolicy {
    /**
     * Identifies unencrypted HTTP endpoints.
     *
     * Uses scheme-level inspection only, allowing callers to pass normalized roots, resolved
     * request URLs, or test server URLs without coupling this policy to Android Uri or OkHttp.
     */
    fun isCleartextHttp(url: String): Boolean =
        url.trimStart().startsWith("http://", ignoreCase = true)

    /**
     * Applies the global HTTP opt-in switch and LAN exceptions.
     *
     * Defaults to blocking HTTP unless the user explicitly enabled cleartext traffic in settings
     * or the target address is verified as a loopback or local network host.
     */
    fun isCleartextHttpAllowed(url: String, settings: AppSettings): Boolean {
        if (!isCleartextHttp(url)) return true
        if (settings.isCleartextTrafficAllowed) return true
        val host = extractHost(url)
        return isLocalOrLan(host)
    }

    /**
     * Parser helper to isolate host for LAN checking.
     * Isolates host address or domain by stripping protocol schemes, port numbers,
     * user credentials, and square brackets for IPv6.
     */
    private fun extractHost(url: String): String {
        var s = url.trimStart()
        val schemeIndex = s.indexOf("://")
        if (schemeIndex != -1) {
            s = s.substring(schemeIndex + 3)
        }
        var end = s.length
        val slashIdx = s.indexOf('/')
        if (slashIdx != -1 && slashIdx < end) end = slashIdx
        val questionIdx = s.indexOf('?')
        if (questionIdx != -1 && questionIdx < end) end = questionIdx
        val hashIdx = s.indexOf('#')
        if (hashIdx != -1 && hashIdx < end) end = hashIdx

        var hostAndPort = s.substring(0, end)
        val atIdx = hostAndPort.lastIndexOf('@')
        if (atIdx != -1) {
            hostAndPort = hostAndPort.substring(atIdx + 1)
        }

        var host = hostAndPort
        if (host.startsWith("[")) {
            val rightBracket = host.indexOf(']')
            if (rightBracket != -1) {
                host = host.substring(1, rightBracket)
            }
        } else {
            val colon = host.indexOf(':')
            if (colon != -1) {
                host = host.substring(0, colon)
            }
        }
        return host.trim()
    }

    /**
     * Categorizes destination IP/domain.
     * Checks if the host string is localhost, an mDNS local domain, a private IPv4 subnet,
     * a link-local IPv4, or a unique local / link-local IPv6 address.
     */
    private fun isLocalOrLan(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (isIPv4(h)) return isLocalOrLanIPv4(h)
        if (isIPv6(h)) return isLocalOrLanIPv6(h)
        return false
    }

    /**
     * Validates standard dotted-quad format.
     * Verifies if the host conforms to the 4-part decimal notation with values between 0 and 255.
     */
    private fun isIPv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
        }
    }

    /**
     * Exempts private subnets and loopbacks.
     * Screens host against loopback (127.0.0.0/8), private LANs (10.0.0.0/8, 172.16.0.0/12,
     * 192.168.0.0/16), and link-local address subnets (169.254.0.0/16).
     */
    private fun isLocalOrLanIPv4(host: String): Boolean {
        val parts = host.split('.').map { it.toInt() }
        val first = parts[0]
        val second = parts[1]
        if (first == 127) return true
        if (first == 10) return true
        if (first == 172 && second in 16..31) return true
        if (first == 192 && second == 168) return true
        if (first == 169 && second == 254) return true
        return false
    }

    /**
     * Syntactic validation for colon presence.
     * Confirms the string represents an IPv6 candidate by verifying the presence of colons
     * and only hex characters or digits (ignoring dot mappings).
     */
    private fun isIPv6(host: String): Boolean {
        if (!host.contains(':')) return false
        val clean = host.replace(":", "").replace(".", "")
        return clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Exempts Unique Local and Link-Local addresses.
     * fc00::/7. and Link-Local Unicast (fe80::/10)
     * by extracting the most significant 16-bit group and performing bitwise shift matching.
     */
    private fun isLocalOrLanIPv6(host: String): Boolean {
        val firstPart = host.substringBefore(':')
        if (firstPart.isEmpty()) {
            return host == "::1" || host == "::"
        }
        val value = firstPart.toIntOrNull(16) ?: return false
        if ((value ushr 9) == 0x7E) return true
        if ((value ushr 6) == 0x3FA) return true
        return false
    }

    /**
     * Stops insecure HTTP before credentials or media requests are sent.
     *
     * Throws a structured policy violation so infrastructure adapters can translate the block into
     * their own domain errors without duplicating the decision rule.
     */
    fun requireCleartextHttpAllowed(url: String, settings: AppSettings, operation: String) {
        if (!isCleartextHttpAllowed(url, settings)) {
            throw UnsafeNetworkPolicyViolation(
                kind = UnsafeNetworkViolationKind.CleartextHttp,
                operation = operation,
                message = "UNSAFE_NETWORK_CLEARTEXT_HTTP_BLOCKED"
            )
        }
    }

    /**
     * Applies the global certificate-bypass opt-in switch.
     *
     * No root-level or credential-level TLS exception is considered valid; the global settings
     * switch is the single source of truth for self-signed or otherwise untrusted certificates.
     */
    fun isInsecureTlsAllowed(settings: AppSettings): Boolean =
        settings.isAllowInsecureTls
}

/**
 * Classifies blocked insecure transport attempts.
 *
 * Gives callers a stable branch key instead of parsing localized exception messages.
 */
enum class UnsafeNetworkViolationKind {
    CleartextHttp
}

/**
 * Signals that a network request violates user settings.
 *
 * Carries the operation label and violation kind so WebDAV, ABS, and playback layers can map the
 * same policy failure into their own logging, availability, or feedback paths.
 */
class UnsafeNetworkPolicyViolation(
    val kind: UnsafeNetworkViolationKind,
    val operation: String,
    message: String
) : IllegalStateException(message)
