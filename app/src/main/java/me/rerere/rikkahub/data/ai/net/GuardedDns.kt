package me.rerere.rikkahub.data.ai.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * True when this address must never be a tool-driven HTTP target: loopback, link-local,
 * site-local (RFC1918), any-local, multicast, CGNAT (100.64.0.0/10), or IPv6 unique-local
 * (fc00::/7).
 *
 * 169.254.169.254, the cloud metadata address, falls out of the link-local check.
 */
internal fun InetAddress.isBlockedTarget(): Boolean {
    if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress ||
        isSiteLocalAddress || isMulticastAddress
    ) {
        return true
    }
    // 100.64.0.0/10 is not covered by isSiteLocalAddress.
    if (this is Inet4Address) {
        val b = address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        if (first == 100 && second in 64..127) return true
    }
    // fc00::/7 (unique-local) is not covered by isSiteLocalAddress, which only matches
    // the deprecated fec0::/10 for Inet6Address.
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return true
    }
    return false
}

/**
 * An OkHttp [Dns] that refuses to resolve a hostname to any private or otherwise
 * non-routable address.
 *
 * Guarding here rather than by inspecting the URL string is deliberate: OkHttp resolves
 * every redirect hop through this same [Dns], so a public URL that 302s to
 * `http://127.0.0.1:8080` is blocked without any extra redirect-tracking logic.
 *
 * The entire answer set is rejected if any address in it is unsafe: accepting the safe
 * subset would let a hostile resolver smuggle a private address alongside a public one.
 */
class GuardedDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowPrivate: Boolean = false,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (allowPrivate) return addresses

        val blocked = addresses.firstOrNull { it.isBlockedTarget() }
        if (blocked != null) {
            throw UnknownHostException(
                "blocked_private_address: $hostname resolves to ${blocked.hostAddress}",
            )
        }
        return addresses
    }
}

private val IPV4_LITERAL_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * True when [host] is an IP literal that [isBlockedTarget] refuses. OkHttp routes a
 * literal-IP host straight to the socket without consulting the custom [Dns] (its route
 * selector short-circuits `canParseAsIpAddress`), so [GuardedDns] never sees it; a caller
 * uses this to reject such a host before dialing.
 *
 * A host counts as a literal when it is an IPv4 dotted-quad or contains ':' (an OkHttp
 * `HttpUrl.host` for IPv6 is bracket-free, and a real hostname never contains ':'). Only
 * literals are inspected, so no DNS query happens here; a hostname returns false. A literal
 * that fails to parse is refused (fail closed) rather than throwing.
 */
internal fun hostIsBlockedLiteral(host: String): Boolean {
    val isLiteral = IPV4_LITERAL_RE.matches(host) || host.contains(':')
    if (!isLiteral) return false
    return try {
        // No DNS lookup happens for an IP literal; getByName just parses it.
        InetAddress.getByName(host).isBlockedTarget()
    } catch (e: UnknownHostException) {
        true
    }
}

/**
 * Wrap [this] client so tool-driven requests cannot reach a private target by either name or
 * literal. The [GuardedDns] wrapper covers hostname resolution on every hop; the network
 * interceptor covers literal-IP hosts, which OkHttp routes without consulting [Dns]. A
 * caller-side [hostIsBlockedLiteral] pre-check on the initial URL makes that case
 * deterministic, while this interceptor still guards literal-IP redirect hops.
 *
 * Also sets a 30 s [OkHttpClient.Builder.callTimeout] bounding the entire call, including
 * trickling reads: a coroutine `withTimeoutOrNull` around a blocking `execute()` has no
 * suspension point to interrupt, so it cannot enforce a caller-side timeout on its own.
 */
internal fun OkHttpClient.withEgressGuard(allowPrivate: Boolean = false): OkHttpClient =
    newBuilder()
        .dns(GuardedDns(Dns.SYSTEM, allowPrivate))
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val host = chain.request().url.host
            if (!allowPrivate && hostIsBlockedLiteral(host)) {
                throw UnknownHostException("blocked_private_address: $host")
            }
            chain.proceed(chain.request())
        }
        .build()
