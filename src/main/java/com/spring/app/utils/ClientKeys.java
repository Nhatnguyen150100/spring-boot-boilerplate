package com.spring.app.utils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns a client address into the key used to throttle it. The single source
 * of client identity for rate limiting ({@code RateLimitFilter}) and login
 * throttling ({@code AuthController}).
 *
 * <p>
 * An IPv6 subscriber normally owns a whole /64, so keying on the full address
 * lets one client rotate through 2^64 addresses and never hit a limit. IPv6
 * addresses are therefore reduced to their /64 prefix; IPv4 addresses (and
 * IPv4-mapped IPv6 addresses, which are IPv4 clients) are used as they are.
 * </p>
 */
public final class ClientKeys {

  /**
   * Only characters that can appear in an IP literal, and it must start with a
   * hex digit or ':' so the JDK never treats the input as a host name to resolve
   * (".1:2" would be looked up in DNS).
   */
  private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:][0-9a-fA-F:.]*");
  private static final Pattern IPV4 = Pattern.compile("(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
  /** "1.2.3.4:5678": group 1 is the address. */
  private static final Pattern IPV4_WITH_PORT = Pattern.compile("(" + IPV4.pattern() + "):\\d{1,5}");
  /** "[2001:db8::1]:443": group 1 is the address. A bare IPv6 literal never has a port. */
  private static final Pattern BRACKETED_WITH_PORT = Pattern.compile("\\[([^\\]]*)\\]:\\d{1,5}");

  private ClientKeys() {
  }

  /** @return true if {@code value} is a bare IPv4 or IPv6 literal (brackets / zone id tolerated for IPv6). */
  public static boolean isIpLiteral(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String candidate = value.trim();
    if (IPV4.matcher(candidate).matches()) {
      return true;
    }
    return parseIpv6(candidate) != null;
  }

  /**
   * The throttling key of the client that sent {@code request}: the peer
   * address, unless the peer is one of {@code trustedProxies}. Then every
   * {@code X-Forwarded-For} line is walked right to left: hops appended by our
   * own proxies are skipped and the first untrusted one is the client. Anything
   * further left was sent by the client itself and can be forged. Without
   * {@code X-Forwarded-For}, {@code X-Real-IP} is used if it is an IP literal.
   * When Tomcat's RemoteIpValve already rewrote the peer to the (untrusted)
   * client, that address is used as it is.
   *
   * @param trustedProxies {@code application.trusted-proxies}; null or empty
   *                       means forwarded headers are never trusted
   * @return the resolved address passed through {@link #clientKey(String)}
   */
  public static String clientKey(HttpServletRequest request, List<String> trustedProxies) {
    return clientKey(clientAddress(request, trustedProxies));
  }

  private static String clientAddress(HttpServletRequest request, List<String> trustedProxies) {
    String remoteAddr = request.getRemoteAddr();
    if (!isTrusted(remoteAddr, trustedProxies)) {
      return remoteAddr;
    }

    // A proxy may append its own header line instead of extending the first
    // one, so every line counts and the client-supplied ones come first.
    String xForwardedFor = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
    if (StringUtils.hasText(xForwardedFor)) {
      String[] hops = xForwardedFor.split(",");
      for (int i = hops.length - 1; i >= 0; i--) {
        String hop = stripPort(hops[i].trim());
        if (!isIpLiteral(hop)) {
          // empty, "unknown", a name or garbage: we cannot tell where the client is
          return remoteAddr;
        }
        if (!isTrusted(hop, trustedProxies)) {
          return hop;
        }
      }
      return remoteAddr;
    }

    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null) {
      String candidate = stripPort(xRealIp.trim());
      if (isIpLiteral(candidate)) {
        return candidate;
      }
    }
    return remoteAddr;
  }

  private static boolean isTrusted(String address, List<String> trustedProxies) {
    if (address == null || trustedProxies == null || trustedProxies.isEmpty()) {
      return false;
    }
    return trustedProxies.stream()
        .filter(proxy -> proxy != null)
        .map(String::trim)
        .anyMatch(address::equals);
  }

  /**
   * @return the unchanged address for IPv4, the IPv4 address for an IPv4-mapped
   *         IPv6 address, {@code "<h>:<h>:<h>:<h>::/64"} for any other IPv6
   *         address (zone id and brackets ignored), and the input unchanged when
   *         it is null, blank or not an IP literal. A trailing port
   *         ({@code 1.2.3.4:5678}, {@code [2001:db8::1]:443}) is dropped first.
   */
  public static String clientKey(String ip) {
    if (ip == null || ip.isBlank()) {
      return ip;
    }
    String candidate = stripPort(ip.trim());
    if (IPV4.matcher(candidate).matches()) {
      return candidate;
    }
    InetAddress address = parseIpv6(candidate);
    if (address == null) {
      return ip;
    }
    if (!(address instanceof Inet6Address)) {
      // IPv4-mapped (::ffff:a.b.c.d): the JDK already unwrapped it to IPv4.
      return address.getHostAddress();
    }

    byte[] bytes = address.getAddress();
    StringBuilder key = new StringBuilder();
    for (int group = 0; group < 4; group++) {
      int value = ((bytes[group * 2] & 0xff) << 8) | (bytes[group * 2 + 1] & 0xff);
      key.append(Integer.toHexString(value)).append(':');
    }
    return key.append(":/64").toString();
  }

  /** Drops a trailing ":port" from "a.b.c.d:port" and "[v6]:port"; anything else is returned as it is. */
  private static String stripPort(String value) {
    Matcher ipv4 = IPV4_WITH_PORT.matcher(value);
    if (ipv4.matches()) {
      return ipv4.group(1);
    }
    Matcher bracketed = BRACKETED_WITH_PORT.matcher(value);
    if (bracketed.matches()) {
      return bracketed.group(1);
    }
    return value;
  }

  /** @return the parsed address if {@code value} is an IPv6 literal (never resolves names), else null */
  private static InetAddress parseIpv6(String value) {
    String literal = value;
    if (literal.startsWith("[") && literal.endsWith("]")) {
      literal = literal.substring(1, literal.length() - 1);
    }
    int zone = literal.indexOf('%');
    if (zone >= 0) {
      literal = literal.substring(0, zone);
    }
    if (literal.indexOf(':') < 0 || !IP_LITERAL.matcher(literal).matches()) {
      return null;
    }
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
