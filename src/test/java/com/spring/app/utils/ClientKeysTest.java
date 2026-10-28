package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientKeysTest {

  @Test
  void ipv4IsUnchanged() {
    assertThat(ClientKeys.clientKey("198.51.100.7")).isEqualTo("198.51.100.7");
  }

  @Test
  void addressesInTheSameSlash64MapToTheSameKey() {
    String a = ClientKeys.clientKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
    String b = ClientKeys.clientKey("2001:db8:1:2::1");

    assertThat(a).isEqualTo(b).isEqualTo("2001:db8:1:2::/64");
  }

  @Test
  void differentSlash64sMapToDifferentKeys() {
    assertThat(ClientKeys.clientKey("2001:db8:1:2::1"))
        .isNotEqualTo(ClientKeys.clientKey("2001:db8:1:3::1"));
  }

  @Test
  void ipv4MappedAddressIsTheIpv4Client() {
    assertThat(ClientKeys.clientKey("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
  }

  @Test
  void zoneIdAndBracketsAreIgnored() {
    assertThat(ClientKeys.clientKey("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
    assertThat(ClientKeys.clientKey("[fe80::2]")).isEqualTo("fe80:0:0:0::/64");
  }

  @Test
  void garbageBlankAndNullAreReturnedUnchanged() {
    assertThat(ClientKeys.clientKey("not-an-ip")).isEqualTo("not-an-ip");
    assertThat(ClientKeys.clientKey("zz::gg")).isEqualTo("zz::gg");
    assertThat(ClientKeys.clientKey("1:2:3")).isEqualTo("1:2:3");
    assertThat(ClientKeys.clientKey(".1:2")).isEqualTo(".1:2");
    assertThat(ClientKeys.clientKey(".:")).isEqualTo(".:");
    assertThat(ClientKeys.clientKey("")).isEmpty();
    assertThat(ClientKeys.clientKey("  ")).isEqualTo("  ");
    assertThat(ClientKeys.clientKey(null)).isNull();
  }

  @Test
  void isIpLiteralAcceptsOnlyAddresses() {
    assertThat(ClientKeys.isIpLiteral("198.51.100.7")).isTrue();
    assertThat(ClientKeys.isIpLiteral("2001:db8::1")).isTrue();
    assertThat(ClientKeys.isIpLiteral("::ffff:1.2.3.4")).isTrue();
    assertThat(ClientKeys.isIpLiteral("999.1.1.1")).isFalse();
    assertThat(ClientKeys.isIpLiteral("example.com")).isFalse();
    assertThat(ClientKeys.isIpLiteral(".1:2")).isFalse();
    assertThat(ClientKeys.isIpLiteral("1.2.3.4:80")).isFalse();
    assertThat(ClientKeys.isIpLiteral(" ")).isFalse();
    assertThat(ClientKeys.isIpLiteral(null)).isFalse();
  }

  // ---------------------------------------------------------------- ports

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "1.2.3.4:5678, 1.2.3.4",
      "[2001:db8:1:2::1]:443, 2001:db8:1:2::/64",
      "[::ffff:1.2.3.4]:80, 1.2.3.4"
  })
  void aTrailingPortIsIgnored(String address, String key) {
    assertThat(ClientKeys.clientKey(address)).isEqualTo(key);
  }

  @Test
  void unbracketedIpv6IsNeverMistakenForAPort() {
    assertThat(ClientKeys.clientKey("2001:db8:1:2::443")).isEqualTo("2001:db8:1:2::/64");
  }

  // ---------------------------------------------------------------- request-level resolution

  private static final List<String> PROXIES = List.of("10.0.0.1", " 10.0.0.2 ");

  private static MockHttpServletRequest from(String remoteAddr, String... forwardedForLines) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    request.setRemoteAddr(remoteAddr);
    for (String line : forwardedForLines) {
      request.addHeader("X-Forwarded-For", line);
    }
    return request;
  }

  @Test
  void untrustedPeerIsTheClientWhateverItForwards() {
    MockHttpServletRequest request = from("198.51.100.7", "203.0.113.9");
    request.addHeader("X-Real-IP", "203.0.113.10");

    assertThat(ClientKeys.clientKey(request, PROXIES)).isEqualTo("198.51.100.7");
  }

  @Test
  void noTrustedProxiesMeansForwardedHeadersAreIgnored() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "203.0.113.9"), List.of())).isEqualTo("10.0.0.1");
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "203.0.113.9"), null)).isEqualTo("10.0.0.1");
  }

  @Test
  void addressAlreadyRewrittenByTheValveIsKept() {
    // RemoteIpValve has set remoteAddr to the client; whatever header is left is ignored.
    assertThat(ClientKeys.clientKey(from("203.0.113.9", "6.6.6.6"), PROXIES)).isEqualTo("203.0.113.9");
  }

  @Test
  void trustedPeerYieldsTheRightMostUntrustedHop() {
    // "6.6.6.6" was typed by the client itself; our proxies appended the rest.
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "6.6.6.6, 203.0.113.9, 10.0.0.2"), PROXIES))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void everyForwardedForLineCountsAndTheClientSuppliedOnesComeFirst() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "6.6.6.6", "203.0.113.9"), PROXIES))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void forwardedHopsWithPortsAreUnderstood() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "203.0.113.9:51234, 10.0.0.2:443"), PROXIES))
        .isEqualTo("203.0.113.9");
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "[2001:db8:1:2::7]:51234"), PROXIES))
        .isEqualTo("2001:db8:1:2::/64");
  }

  @Test
  void aNonIpHopFallsBackToThePeer() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "203.0.113.9, evil.example.com"), PROXIES))
        .isEqualTo("10.0.0.1");
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "203.0.113.9, unknown"), PROXIES)).isEqualTo("10.0.0.1");
  }

  @Test
  void onlyTrustedHopsMeansThePeer() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "10.0.0.2"), PROXIES)).isEqualTo("10.0.0.1");
  }

  @Test
  void realIpIsUsedOnlyWithoutForwardedForAndOnlyIfItIsAnAddress() {
    MockHttpServletRequest withRealIp = from("10.0.0.1");
    withRealIp.addHeader("X-Real-IP", " 203.0.113.9 ");
    assertThat(ClientKeys.clientKey(withRealIp, PROXIES)).isEqualTo("203.0.113.9");

    MockHttpServletRequest withGarbage = from("10.0.0.1");
    withGarbage.addHeader("X-Real-IP", "evil.example.com");
    assertThat(ClientKeys.clientKey(withGarbage, PROXIES)).isEqualTo("10.0.0.1");

    MockHttpServletRequest withUnknown = from("10.0.0.1");
    withUnknown.addHeader("X-Real-IP", "unknown");
    assertThat(ClientKeys.clientKey(withUnknown, PROXIES)).isEqualTo("10.0.0.1");
  }

  @Test
  void theResolvedIpv6ClientIsReducedToItsSlash64() {
    assertThat(ClientKeys.clientKey(from("10.0.0.1", "2001:db8:1:2:aaaa:bbbb:cccc:dddd"), PROXIES))
        .isEqualTo("2001:db8:1:2::/64");
    assertThat(ClientKeys.clientKey(from("2001:db8:1:2::1"), PROXIES)).isEqualTo("2001:db8:1:2::/64");
  }
}
