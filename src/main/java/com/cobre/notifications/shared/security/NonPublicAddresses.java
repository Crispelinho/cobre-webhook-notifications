package com.cobre.notifications.shared.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Address ranges a webhook must never reach: loopback, private, link-local (including cloud metadata endpoints),
 * unique-local, carrier-grade NAT, multicast, unspecified, documentation and other special-purpose ranges from the
 * IANA registries. IPv6 transition ranges that embed an IPv4 address (NAT64, 6to4, Teredo, IPv4-mapped) are blocked
 * as a whole, so an internal IPv4 address cannot be reached through them.
 */
final class NonPublicAddresses {

	private static final List<Range> RANGES = List.of(
			// IPv4
			Range.of("0.0.0.0/8"), // "this network", including the unspecified address
			Range.of("10.0.0.0/8"), // private
			Range.of("100.64.0.0/10"), // carrier-grade NAT
			Range.of("127.0.0.0/8"), // loopback
			Range.of("169.254.0.0/16"), // link-local, including 169.254.169.254 (cloud metadata)
			Range.of("172.16.0.0/12"), // private
			Range.of("192.0.0.0/24"), // IETF protocol assignments
			Range.of("192.0.2.0/24"), // documentation
			Range.of("192.88.99.0/24"), // 6to4 relay anycast
			Range.of("192.168.0.0/16"), // private
			Range.of("198.18.0.0/15"), // benchmarking
			Range.of("198.51.100.0/24"), // documentation
			Range.of("203.0.113.0/24"), // documentation
			Range.of("224.0.0.0/4"), // multicast
			Range.of("240.0.0.0/4"), // reserved, including the broadcast address
			// IPv6
			Range.of("::/96"), // unspecified, loopback and IPv4-compatible
			Range.of("::ffff:0:0/96"), // IPv4-mapped
			Range.of("64:ff9b::/96"), // NAT64
			Range.of("64:ff9b:1::/48"), // local-use NAT64
			Range.of("100::/64"), // discard-only
			Range.of("2001::/23"), // IETF protocol assignments, including Teredo
			Range.of("2001:db8::/32"), // documentation
			Range.of("2002::/16"), // 6to4
			Range.of("fc00::/7"), // unique-local, including fd00:ec2::254 (cloud metadata)
			Range.of("fe80::/10"), // link-local
			Range.of("fec0::/10"), // deprecated site-local
			Range.of("ff00::/8")); // multicast

	private NonPublicAddresses() {
	}

	static boolean isPublic(InetAddress address) {
		byte[] bytes = address.getAddress();
		return RANGES.stream().noneMatch(range -> range.contains(bytes));
	}

	private record Range(byte[] network, int prefixLength) {

		static Range of(String cidr) {
			int slash = cidr.indexOf('/');
			// Literal addresses only: InetAddress does not query DNS for them.
			byte[] network = literal(cidr.substring(0, slash));
			return new Range(network, Integer.parseInt(cidr.substring(slash + 1)));
		}

		boolean contains(byte[] address) {
			if (address.length != network.length) {
				return false;
			}
			int fullBytes = prefixLength / 8;
			for (int i = 0; i < fullBytes; i++) {
				if (address[i] != network[i]) {
					return false;
				}
			}
			int remainingBits = prefixLength % 8;
			if (remainingBits == 0) {
				return true;
			}
			int mask = 0xFF << (8 - remainingBits) & 0xFF;
			return (address[fullBytes] & mask) == (network[fullBytes] & mask);
		}

		private static byte[] literal(String address) {
			try {
				byte[] bytes = InetAddress.getByName(address).getAddress();
				// InetAddress turns IPv4-mapped IPv6 literals into IPv4; keep the 16-byte form for the range.
				return address.contains(":") && bytes.length == 4 ? mapped(bytes) : bytes;
			}
			catch (UnknownHostException ex) {
				throw new IllegalArgumentException(address, ex);
			}
		}

		private static byte[] mapped(byte[] ipv4) {
			byte[] bytes = new byte[16];
			bytes[10] = (byte) 0xFF;
			bytes[11] = (byte) 0xFF;
			System.arraycopy(ipv4, 0, bytes, 12, 4);
			return bytes;
		}

	}

}
