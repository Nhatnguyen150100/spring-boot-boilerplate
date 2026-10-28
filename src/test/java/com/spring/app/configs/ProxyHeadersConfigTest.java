package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import com.spring.app.configs.properties.ApplicationProperties;

class ProxyHeadersConfigTest {

  private static RemoteIpValve customizedValve(List<String> trustedProxies) {
    ApplicationProperties properties = new ApplicationProperties();
    properties.setTrustedProxies(trustedProxies);
    RemoteIpValve valve = new RemoteIpValve();
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addEngineValves(valve);

    new ProxyHeadersConfig(properties).remoteIpValveCustomizer().customize(factory);
    return valve;
  }

  @Test
  void stockValveTrustsEveryPrivateAddress() {
    // The problem being fixed: out of the box any 10.x / 192.168.x peer may set X-Forwarded-For.
    String stock = new RemoteIpValve().getInternalProxies();
    assertThat(Pattern.compile(stock).matcher("10.9.8.7").matches()).isTrue();
  }

  @Test
  void onlyConfiguredTrustedProxiesAreInternal() {
    RemoteIpValve valve = customizedValve(List.of("10.0.0.1", " 172.18.0.5 "));

    Pattern internal = Pattern.compile(valve.getInternalProxies());
    assertThat(internal.matcher("10.0.0.1").matches()).isTrue();
    assertThat(internal.matcher("172.18.0.5").matches()).isTrue();
    assertThat(internal.matcher("10.0.0.2").matches()).isFalse();
    assertThat(internal.matcher("10.0.0.10").matches()).isFalse();
    assertThat(internal.matcher("192.168.1.10").matches()).isFalse();
  }

  @Test
  void dotsInConfiguredAddressesAreLiteral() {
    Pattern internal = Pattern.compile(customizedValve(List.of("10.0.0.1")).getInternalProxies());

    assertThat(internal.matcher("10x0x0x1").matches()).isFalse();
    assertThat(internal.matcher("110.0.0.1").matches()).isFalse();
    assertThat(internal.matcher("10.0.0.1").matches()).isTrue();
  }

  @Test
  void noTrustedProxiesMeansForwardedHeadersAreNeverTrusted() {
    assertThat(customizedValve(List.of()).getInternalProxies()).isNull();
    assertThat(customizedValve(List.of(" ")).getInternalProxies()).isNull();
  }

  @Test
  void runsAfterBootsOwnTomcatCustomizer() {
    // Boot's TomcatWebServerFactoryCustomizer (order 0) creates the valve; this one must run later.
    assertThat(new ProxyHeadersConfig(new ApplicationProperties()).remoteIpValveCustomizer().getOrder())
        .isGreaterThan(0);
  }
}
