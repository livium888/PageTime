package com.pagetime.app.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client factory for all network access (Gutendex catalog, book
 * downloads, cover images).
 *
 * Why the custom DNS: the book sources sit behind CDNs (e.g. gutendex.com is on
 * Cloudflare) that advertise both IPv6 and IPv4. On many mobile networks the IPv6
 * path is broken or partially provisioned, so connecting to an AAAA address dies
 * with "Failed to connect". Ordering resolved addresses IPv4-first makes those
 * networks work immediately while IPv6 remains available as a fallback elsewhere.
 */
object AppHttp {

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            InetAddress.getAllByName(hostname).sortedByDescending { it is Inet4Address }
    }

    /**
     * @param callTimeoutSeconds hard cap for the whole exchange (connect + headers +
     *   body). Use ~90s for JSON APIs; use 0 (no cap) for large file downloads,
     *   where total time legitimately varies with connection speed.
     */
    fun newClient(callTimeoutSeconds: Long = 0L): OkHttpClient =
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
