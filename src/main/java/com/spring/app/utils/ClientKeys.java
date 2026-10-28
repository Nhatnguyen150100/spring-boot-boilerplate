package com.spring.app.utils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Turns a client address into the key used to throttle it.
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
   * @return the unchanged address for IPv4, the IPv4 address for an IPv4-mapped
   *         IPv6 address, {@code "<h>:<h>:<h>:<h>::/64"} for any other IPv6
   *         address (zone id and brackets ignored), and the input unchanged when
   *         it is null, blank or not an IP literal
   */
  public static String clientKey(String ip) {
    if (ip == null || ip.isBlank()) {
      return ip;
    }
    InetAddress address = parseIpv6(ip.trim());
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
