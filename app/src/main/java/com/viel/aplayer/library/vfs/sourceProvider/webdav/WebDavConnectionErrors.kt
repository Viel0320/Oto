package com.viel.aplayer.library.vfs.sourceProvider.webdav

import java.io.IOException

/**
 * WebDAV Endpoint Validation Reason (Stable code for malformed WebDAV endpoint inputs)
 * Infrastructure callers throw this code instead of localized text so SettingsViewModel can map each case to Android string resources.
 */
enum class WebDavEndpointValidationReason(val code: String) {
    MissingScheme("WEBDAV_URL_MISSING_SCHEME"),
    MissingHost("WEBDAV_URL_MISSING_HOST"),
    UserInfoNotAllowed("WEBDAV_USERINFO_NOT_ALLOWED"),
    UnsupportedScheme("WEBDAV_URL_UNSUPPORTED_SCHEME")
}

/**
 * WebDAV Endpoint Validation Exception (Carries endpoint validation reason without user-facing copy)
 * The exception message is intentionally a stable code because localized detail rendering belongs to the settings UI boundary.
 */
class WebDavEndpointValidationException(
    val reason: WebDavEndpointValidationReason
) : IllegalArgumentException(reason.code)

/**
 * WebDAV Connection Test Failure Reason (Stable code for PROPFIND connection-test failures)
 * HTTP response details stay typed so settings feedback can be translated without parsing server status messages.
 */
enum class WebDavConnectionTestFailureReason {
    Unauthorized,
    Forbidden,
    NotFound,
    HttpStatus
}

/**
 * WebDAV Connection Test Exception (Carries connection-test failure data without localized text)
 * The optional HTTP status remains available for generic server failures while avoiding hard-coded Chinese error strings in the network adapter.
 */
class WebDavConnectionTestException(
    val reason: WebDavConnectionTestFailureReason,
    val httpCode: Int? = null
) : IOException(reason.name)
