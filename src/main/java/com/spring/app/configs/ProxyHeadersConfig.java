package com.spring.app.configs;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.spring.app.configs.properties.ApplicationProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes Tomcat's {@link RemoteIpValve} (created by Boot when
 * {@code server.forward-headers-strategy=native}, i.e. in prod) trust
 * {@code X-Forwarded-For} / {@code X-Forwarded-Proto} only from the peers in
 * {@code application.trusted-proxies} - the same list {@code ClientKeys} (rate
 * limiting, login throttling) uses. Tomcat's default trusts every private-range peer, so any container on
 * a shared network could choose its own client IP.
 *
 * <p>
 * Addresses are matched exactly as Tomcat reports the peer
 * ({@code request.getRemoteAddr()}); write IPv6 entries in that full form.
 * With an empty list no peer is trusted and the forwarded headers are ignored
 * (logged once at WARN when the valve is present).
 * When the strategy is not {@code native} there is no valve and this is a no-op.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ProxyHeadersConfig {

  private final ApplicationProperties applicationProperties;

  @Bean
  RemoteIpValveCustomizer remoteIpValveCustomizer() {
    return new RemoteIpValveCustomizer(toInternalProxiesRegex(applicationProperties.getTrustedProxies()));
  }

  /**
   * Exact-match regex for the given IPs, or {@code null} (= trust nobody) when
   * there are none. Every address is quoted so {@code .} is literal; Tomcat
   * applies the pattern with {@code Matcher#matches()}, so it is anchored to the
   * whole peer address.
   */
  static String toInternalProxiesRegex(List<String> trustedProxies) {
    if (trustedProxies == null) {
      return null;
    }
    String regex = trustedProxies.stream()
        .filter(address -> address != null)
        .map(String::trim)
        .filter(address -> !address.isEmpty())
        .map(Pattern::quote)
        .collect(Collectors.joining("|"));
    return regex.isEmpty() ? null : regex;
  }

  static final class RemoteIpValveCustomizer
      implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private final String internalProxiesRegex;

    RemoteIpValveCustomizer(String internalProxiesRegex) {
      this.internalProxiesRegex = internalProxiesRegex;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
      List<RemoteIpValve> valves = factory.getEngineValves().stream()
          .filter(RemoteIpValve.class::isInstance)
          .map(RemoteIpValve.class::cast)
          .toList();
      valves.forEach(valve -> valve.setInternalProxies(internalProxiesRegex));
      if (!valves.isEmpty() && internalProxiesRegex == null) {
        log.warn("server.forward-headers-strategy=native but application.trusted-proxies is empty: "
            + "X-Forwarded-For and X-Forwarded-Proto are ignored, so every client appears as the proxy's "
            + "address and requests forwarded over HTTPS look like plain HTTP. Set TRUSTED_PROXIES.");
      }
    }

    /** After Boot's TomcatWebServerFactoryCustomizer (order 0), which adds the valve. */
    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
