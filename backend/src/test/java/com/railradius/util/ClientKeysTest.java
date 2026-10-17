package com.railradius.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientKeysTest {

    @Test
    void ipv4AddressIsItsOwnKey() {
        assertThat(ClientKeys.rateLimitKey("192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void ipv6AddressIsKeyedByItsSlash64() {
        assertThat(ClientKeys.rateLimitKey("2001:db8:1:2::5")).isEqualTo("2001:db8:1:2::/64");
    }

    @Test
    void ipv6AddressesInOneSlash64ShareAKey() {
        assertThat(ClientKeys.rateLimitKey("2001:db8:1:2:aaaa::1"))
                .isEqualTo(ClientKeys.rateLimitKey("2001:db8:1:2:ffff:ffff:ffff:ffff"));
    }

    @Test
    void ipv6AddressesInDifferentSlash64sHaveDifferentKeys() {
        assertThat(ClientKeys.rateLimitKey("2001:db8:1:2::1")).isNotEqualTo(ClientKeys.rateLimitKey("2001:db8:1:3::1"));
    }

    @Test
    void everySpellingOfAnIpv6AddressGivesTheSameKey() {
        String key = ClientKeys.rateLimitKey("2001:db8:1:2::5");
        assertThat(ClientKeys.rateLimitKey("2001:0db8:0001:0002:0000:0000:0000:0005")).isEqualTo(key);
        assertThat(ClientKeys.rateLimitKey("2001:DB8:1:2::5")).isEqualTo(key);
        assertThat(ClientKeys.rateLimitKey("2001:db8:1:2::5%eth0")).isEqualTo(key);
        assertThat(ClientKeys.rateLimitKey("[2001:db8:1:2::5]")).isEqualTo(key);
        assertThat(ClientKeys.rateLimitKey("[2001:db8:1:2::5%3]")).isEqualTo(key);
    }

    @Test
    void ipv4MappedIpv6AddressIsKeyedAsTheIpv4Address() {
        assertThat(ClientKeys.rateLimitKey("::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void unparsableAddressIsItsOwnKey() {
        assertThat(ClientKeys.rateLimitKey("")).isEmpty();
        assertThat(ClientKeys.rateLimitKey("not-an-ip")).isEqualTo("not-an-ip");
        assertThat(ClientKeys.rateLimitKey("foo:bar")).isEqualTo("foo:bar");
        assertThat(ClientKeys.rateLimitKey("2001:db8::1::2")).isEqualTo("2001:db8::1::2");
    }
}
