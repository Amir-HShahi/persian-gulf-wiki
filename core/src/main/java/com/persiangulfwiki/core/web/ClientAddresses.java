package com.persiangulfwiki.core.web;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;

// Who is calling, as far as a per-client rate limit can tell -- never an identity, and never
// anything to authorize on.
//
// CF-Connecting-IP first: in staging and production the app is reached only through the
// Cloudflare Tunnel (docker-compose.deploy.yml binds its port to loopback for exactly this), and
// Cloudflare sets that header to the visitor's address, replacing any value the visitor sent.
// X-Forwarded-For is not used: its first entry is whatever the client wrote. Without the header
// -- local dev, a test -- the socket's address is used, which server.forward-headers-strategy
// may itself have taken from X-Forwarded-For; harmless where no tunnel stands in front.
//
// An IPv6 address is reduced to its /64, the block one subscriber is normally given, so a client
// cannot reset its limit by rotating through the addresses it owns.
public final class ClientAddresses {

    static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final int IPV6_PREFIX_BYTES = 8;
    // Longer than any IP literal; anything longer is not one and is not parsed.
    private static final int MAX_LITERAL_LENGTH = 64;

    private ClientAddresses() {
    }

    // A short, bounded string fit for a Redis key: "v4:<address>", "v6:<first 64 bits in hex>",
    // or, for a remote address that is no IP literal at all, "raw:<it>" cut to length.
    public static String rateLimitBucket(HttpServletRequest request) {
        String forwarded = request.getHeader(CF_CONNECTING_IP);
        if (forwarded != null) {
            String bucket = bucketOf(forwarded.strip());
            if (bucket != null) {
                return bucket;
            }
        }
        String remote = request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
        String bucket = bucketOf(remote);
        return bucket != null ? bucket : "raw:" + remote.substring(0, Math.min(remote.length(), MAX_LITERAL_LENGTH));
    }

    // ofLiteral, not getByName: it never resolves a host name, so a header value can never make
    // the app perform a DNS lookup. Null when the value is not an IP literal.
    private static @Nullable String bucketOf(String value) {
        if (value.isEmpty() || value.length() > MAX_LITERAL_LENGTH) {
            return null;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(value);
            if (address instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(Arrays.copyOf(address.getAddress(), IPV6_PREFIX_BYTES));
            }
            return "v4:" + address.getHostAddress();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
