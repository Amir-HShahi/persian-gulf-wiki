package com.persiangulfwiki.core.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressesTests {

    private static MockHttpServletRequest from(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static MockHttpServletRequest from(String remoteAddr, String connectingIp) {
        MockHttpServletRequest request = from(remoteAddr);
        request.addHeader(ClientAddresses.CF_CONNECTING_IP, connectingIp);
        return request;
    }

    @Test
    void usesTheSocketAddressWithoutTheCloudflareHeader() {
        assertThat(ClientAddresses.rateLimitBucket(from("203.0.113.9"))).isEqualTo("v4:203.0.113.9");
        assertThat(ClientAddresses.rateLimitBucket(from("2001:db8:1:2:3:4:5:6"))).isEqualTo("v6:20010db800010002");
    }

    // Behind the tunnel every request's socket is the tunnel's; the visitor is in the header.
    @Test
    void prefersAValidCloudflareConnectingIp() {
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "198.51.100.7"))).isEqualTo("v4:198.51.100.7");
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "  198.51.100.7 "))).isEqualTo("v4:198.51.100.7");
    }

    // A subscriber normally holds a whole /64, so rotating through it must not reset the limit,
    // while the next /64 over is somebody else.
    @Test
    void groupsIpv6AddressesByTheirSlash64() {
        String bucket = ClientAddresses.rateLimitBucket(from("127.0.0.1", "2001:db8:abcd:12::1"));
        assertThat(bucket).isEqualTo("v6:20010db8abcd0012");
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "2001:db8:abcd:12:ffff:ffff:ffff:ffff")))
                .isEqualTo(bucket);
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "2001:0DB8:ABCD:0012:0:0:0:9"))).isEqualTo(bucket);
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "2001:db8:abcd:13::1"))).isNotEqualTo(bucket);
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "::1"))).isEqualTo("v6:0000000000000000");
    }

    // An IPv4-mapped IPv6 literal is that IPv4 client, not the ::ffff:0:0/96 block shared by all.
    @Test
    void readsAnIpv4MappedIpv6AddressAsItsIpv4Address() {
        assertThat(ClientAddresses.rateLimitBucket(from("127.0.0.1", "::ffff:192.0.2.44"))).isEqualTo("v4:192.0.2.44");
        assertThat(ClientAddresses.rateLimitBucket(from("::ffff:192.0.2.45"))).isEqualTo("v4:192.0.2.45");
    }

    // ofLiteral never resolves a name, so a header can neither steer the bucket by DNS nor make
    // the app perform a lookup; anything that is not an IP literal is ignored.
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "localhost", "example.org", "198.51.100.7, 10.0.0.1", "198.51.100.7:443",
            "999.1.1.1", "unknown", "2001:db8::1::2", "<script>"})
    void ignoresACloudflareHeaderThatIsNoIpLiteral(String header) {
        assertThat(ClientAddresses.rateLimitBucket(from("203.0.113.9", header))).isEqualTo("v4:203.0.113.9");
    }

    @Test
    void ignoresAnOverlongCloudflareHeader() {
        String overlong = "1".repeat(65);
        assertThat(ClientAddresses.rateLimitBucket(from("203.0.113.9", overlong))).isEqualTo("v4:203.0.113.9");
        String padded = "0000:0000:0000:0000:0000:0000:0000:0001" + " ".repeat(30);
        assertThat(ClientAddresses.rateLimitBucket(from("203.0.113.9", padded))).isEqualTo("v6:0000000000000000");
    }

    // A remote address that is no literal (a unix socket, a proxy writing a name) still gets a
    // bounded bucket of its own.
    @Test
    void fallsBackToABoundedRawBucketForANonLiteralRemoteAddress() {
        assertThat(ClientAddresses.rateLimitBucket(from("tunnel.local"))).isEqualTo("raw:tunnel.local");
        assertThat(ClientAddresses.rateLimitBucket(from("h".repeat(200)))).isEqualTo("raw:" + "h".repeat(64));
        assertThat(ClientAddresses.rateLimitBucket(from("", "garbage"))).isEqualTo("raw:");
    }
}
