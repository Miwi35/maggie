<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

/** Whether an IP address belongs to the public internet: loopback, private, link-local (cloud metadata), CGNAT, documentation and multicast ranges are not. */
final class PublicAddress
{
    private const BLOCKED_V4 = [
        '0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8', '169.254.0.0/16',
        '172.16.0.0/12', '192.0.0.0/24', '192.0.2.0/24', '192.88.99.0/24', '192.168.0.0/16',
        '198.18.0.0/15', '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/4', '240.0.0.0/4',
    ];

    private const BLOCKED_V6 = [
        '::/96', '64:ff9b:1::/48', '100::/64', '2001::/23', '2001:db8::/32',
        '2002::/16', 'fc00::/7', 'fe80::/10', 'fec0::/10', 'ff00::/8',
    ];

    public static function isPublic(string $address): bool
    {
        $packed = @inet_pton($address);
        if (false === $packed) {
            return false;
        }

        if (16 === \strlen($packed)) {
            // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) addresses are judged as the IPv4 they carry.
            if (str_starts_with($packed, str_repeat("\0", 10)."\xff\xff") || str_starts_with($packed, "\x00\x64\xff\x9b".str_repeat("\0", 8))) {
                return self::isPublic(inet_ntop(substr($packed, 12)));
            }

            return !self::inAny($packed, self::BLOCKED_V6);
        }

        return !self::inAny($packed, self::BLOCKED_V4);
    }

    /** @param list<string> $ranges */
    private static function inAny(string $packed, array $ranges): bool
    {
        foreach ($ranges as $range) {
            [$network, $bits] = explode('/', $range);
            $networkPacked = inet_pton($network);
            $bits = (int) $bits;
            $bytes = intdiv($bits, 8);

            if (substr($packed, 0, $bytes) !== substr($networkPacked, 0, $bytes)) {
                continue;
            }
            $rest = $bits % 8;
            if (0 === $rest) {
                return true;
            }
            $mask = (0xFF << (8 - $rest)) & 0xFF;
            if ((\ord($packed[$bytes]) & $mask) === (\ord($networkPacked[$bytes]) & $mask)) {
                return true;
            }
        }

        return false;
    }
}
