package com.viel.aplayer.abs.net

import com.viel.aplayer.abs.auth.AbsCredentialStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Authentication Interceptor for Audiobookshelf OkHttp client.
 * Extracts the [AbsAuth] tag from the request to dynamically inject
 * the "Authorization: Bearer <token>" header before the request is sent.
 */
class AbsAuthInterceptor(
    private val credentialStore: AbsCredentialStore? = null
) : Interceptor {
    
    /**
     * Intercepts the HTTP request, looks for [AbsAuth] tag, and appends
     * the Authorization header if a valid token is found or resolved.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val auth = originalRequest.tag(AbsAuth::class.java) ?: return chain.proceed(originalRequest)
        
        // Resolve token from credentialStore using credentialId if token is null/blank,
        // falling back to the raw token.
        val token = auth.credentialId?.let { id ->
            credentialStore?.let { store ->
                runBlocking { store.get(id)?.token }
            }
        } ?: auth.token
        
        if (token.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }
        
        val newRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(newRequest)
    }
}
