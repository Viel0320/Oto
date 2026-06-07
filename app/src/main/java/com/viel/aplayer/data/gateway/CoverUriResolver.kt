// Domain Isolation: Define a clean domain-oriented URI resolver to strip Android platform dependencies out of core repositories.
package com.viel.aplayer.data.gateway

/**
 * Cover URI Resolver Interface (CoverUriResolver)
 * 
 * Provides an abstract mechanism to resolve raw artwork absolute file paths 
 * into content URLs suitable for background player notification assets.
 */
interface CoverUriResolver {

    /**
     * Map absolute path to content URI string (To decouple Android FileProvider from data domain)
     * 
     * @param absolutePath Absolute local file path of the artwork image.
     * @return Fully formatted content URI string, or null if file does not exist.
     */
    fun toContentUri(absolutePath: String): String?
}
