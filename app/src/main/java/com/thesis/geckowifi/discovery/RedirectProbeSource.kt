package com.thesis.geckowifi.discovery

import android.annotation.SuppressLint
import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "PortalDiscovery"

/**
 * Captive-portal discovery without root, the way Android itself detects
 * portals: request a plain-HTTP URL that normally answers 204 and follow
 * whatever the network sends back - hop by hop, by hand, recording each hop's
 * TLS key.
 *
 * With openNDS on router A the chain looks like:
 *  1. http://connectivitycheck.gstatic.com/generate_204 → 302 (openNDS, spoofed answer)
 *  2. http://portal.gecko-a.lab/?tok=… → 302 (portal server, upgrade to HTTPS)
 *  3. https://portal.gecko-a.lab/?tok=… → 200 login page   ← judged against the GeoCert
 *
 * Only redirects are followed (3xx Location, HTML meta refresh) - never form
 * submissions or links, so hops that need a user click (e.g. the payment
 * delegate) are not seen here; see docs/portal-detection.md.
 */
class RedirectProbeSource(
    private val probeUrl: String = DEFAULT_PROBE_URL,
    private val maxHops: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis
) : HopSource {

    override val kind = HopSource.Kind.REDIRECT_PROBE

    override suspend fun discover(network: Network): Discovery = withContext(Dispatchers.IO) {
        val peer = PeerCertificateCapture()
        val client = clientFor(network, peer)
        val hops = mutableListOf<ObservedHop>()
        var url = probeUrl
        val seen = mutableSetOf<String>()

        for (index in 0 until maxHops) {
            if (!seen.add(url)) return@withContext Discovery(Discovery.Outcome.Failed("redirect loop at $url"), hops)
            val parsed = runCatching { java.net.URI(url) }.getOrNull()
            val scheme = parsed?.scheme ?: "http"
            val host = parsed?.host.orEmpty()
            val port = parsed?.port?.takeIf { it > 0 } ?: if (scheme == "https") 443 else 80
            peer.leaf = null
            val response = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
            } catch (e: Exception) {
                hops += ObservedHop(index, url, scheme, host, port, source = kind,
                    timestampMillis = clock(), error = e.message ?: e::class.java.simpleName)
                Log.w(TAG, "hop $index $url failed", e)
                return@withContext Discovery(Discovery.Outcome.Failed("request to $host failed: ${e.message}"), hops)
            }
            val hop = response.use { observe(index, url, scheme, host, port, it, peer.leaf) }
            hops += hop
            Log.i(TAG, "hop $index ${hop.statusCode} $url -> ${hop.next ?: "(end)"} spki=${hop.presentedSpkiHash}")

            when {
                index == 0 && hop.statusCode == 204 -> return@withContext Discovery(Discovery.Outcome.NoPortal, hops)
                hop.next != null -> url = hop.next
                else -> return@withContext Discovery(Discovery.Outcome.PortalReached, hops)
            }
        }
        Discovery(Discovery.Outcome.Failed("more than $maxHops redirects"), hops)
    }

    private fun observe(
        index: Int, url: String, scheme: String, host: String, port: Int,
        response: Response, leaf: X509Certificate?
    ): ObservedHop {
        // Not response.handshake.peerCertificates: OkHttp "cleans" that chain against the
        // system trust store and silently returns an EMPTY list for any certificate it can't
        // chain to a system CA - i.e. every lab-CA/self-signed portal cert, which then looked
        // like "no key presented" (false CONFLICT). The raw TLS session's certificate is taken
        // via PeerCertificateCapture instead.
        val cert = if (scheme.equals("https", ignoreCase = true)) leaf else null
        val next = when {
            response.isRedirect -> response.header("Location")
            response.header("Content-Type")?.contains("html", ignoreCase = true) == true ->
                RedirectParsing.metaRefreshTarget(response.peekBody(MAX_HTML_BYTES).string())
            else -> null
        }?.let { RedirectParsing.resolve(url, it) }
        return ObservedHop(
            index = index, url = url, scheme = scheme, host = host, port = port,
            statusCode = response.code, next = next,
            presentedSpkiHash = cert?.let(RedirectParsing::spkiHash),
            certificateSubject = cert?.subjectX500Principal?.name,
            source = kind, timestampMillis = clock()
        )
    }

    companion object {
        /** Same probe Android's own captive-portal detection uses. Needs DNS on the network (A forwards it). */
        const val DEFAULT_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
        private const val USER_AGENT = "GeckoWiFi-PortalProbe/1.0"
        private const val MAX_HTML_BYTES = 16L * 1024

        /**
         * Bound to the joined WiFi (it is never the default network), no
         * automatic redirects (each hop must be seen), and NO certificate
         * validation: portal certificates are self-signed / lab-CA signed and
         * the trust decision is the GeoCert pin comparison afterwards. This
         * client only ever sends GETs without credentials, so accepting any
         * certificate leaks nothing.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private fun clientFor(network: Network, peer: PeerCertificateCapture): OkHttpClient {
            val acceptAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAll), SecureRandom()) }
            return OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
                .sslSocketFactory(tls.socketFactory, acceptAll)
                .hostnameVerifier { _, _ -> true }
                .eventListener(peer)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Records the leaf certificate of the TLS session each call actually used
     * (new or pooled connection), straight from the SSLSocket - uncleaned.
     * Calls run one after another, so one field is enough; [discover] resets
     * it before each request.
     */
    private class PeerCertificateCapture : EventListener() {
        @Volatile var leaf: X509Certificate? = null

        override fun connectionAcquired(call: Call, connection: Connection) {
            leaf = runCatching {
                (connection.socket() as? SSLSocket)?.session?.peerCertificates?.firstOrNull() as? X509Certificate
            }.getOrNull()
        }
    }
}
