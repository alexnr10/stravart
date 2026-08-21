package com.stravart.core.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Abstraction minimale d'un client HTTP, pour pouvoir simuler le réseau dans les tests. */
fun interface HttpClient {
    /** @throws HttpException si le serveur répond autre chose qu'un 2xx. */
    @Throws(IOException::class)
    fun get(url: String): String
}

class HttpException(val status: Int, message: String) : IOException(message)

/**
 * Implémentation basée sur [HttpURLConnection] : disponible aussi bien sur la JVM
 * que sur Android, sans dépendance supplémentaire.
 */
class JdkHttpClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 45_000,
) : HttpClient {

    override fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { raw ->
                val decoded = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(raw)
                } else {
                    raw
                }
                decoded.bufferedReader().use { it.readText() }
            }.orEmpty()
            if (status !in 200..299) {
                throw HttpException(status, "HTTP $status: ${body.take(300)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Les serveurs communautaires (BRouter, Nominatim) exigent un User-Agent
         * identifiable : le leur cacher est le meilleur moyen de se faire bannir.
         */
        const val DEFAULT_USER_AGENT = "StravArt/1.0 (https://github.com/alexnr10/stravart)"
    }
}
