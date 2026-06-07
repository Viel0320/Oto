package com.viel.aplayer.library.vfs.sourceProvider.webdav

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV Protocol Constants (Centralizes shared request payloads and status codes)
 * Connection tests and source browsing both issue lightweight PROPFIND requests, so this module keeps protocol literals from drifting across callers.
 */
internal object WebDavProtocol {
    val PROPFIND_ALL_PROPERTIES_BODY: RequestBody =
        """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())

    const val HTTP_MULTI_STATUS = 207
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
}
