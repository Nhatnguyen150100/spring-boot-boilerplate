package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
    assertThat(ClientKeys.clientKey("")).isEmpty();
    assertThat(ClientKeys.clientKey("  ")).isEqualTo("  ");
    assertThat(ClientKeys.clientKey(null)).isNull();
  }
}
