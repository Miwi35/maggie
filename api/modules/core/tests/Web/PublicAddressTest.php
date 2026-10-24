<?php

namespace Maggie\Core\Tests\Web;

use Maggie\Core\Web\PublicAddress;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class PublicAddressTest extends TestCase
{
    /** @return iterable<string, array{string}> */
    public static function privateOrReserved(): iterable
    {
        yield 'loopback' => ['127.0.0.1'];
        yield 'loopback range' => ['127.8.8.8'];
        yield 'this network' => ['0.0.0.0'];
        yield 'private 10/8' => ['10.0.0.1'];
        yield 'private 172.16/12' => ['172.31.255.254'];
        yield 'private 192.168/16' => ['192.168.1.20'];
        yield 'cloud metadata' => ['169.254.169.254'];
        yield 'carrier-grade NAT' => ['100.64.0.1'];
        yield 'alibaba metadata' => ['100.100.100.200'];
        yield 'documentation' => ['203.0.113.7'];
        yield 'multicast' => ['224.0.0.1'];
        yield 'reserved' => ['240.0.0.1'];
        yield 'broadcast' => ['255.255.255.255'];
        yield 'ipv6 loopback' => ['::1'];
        yield 'ipv6 unspecified' => ['::'];
        yield 'ipv6 unique local' => ['fd00:ec2::254'];
        yield 'ipv6 link-local' => ['fe80::1'];
        yield 'ipv6 6to4' => ['2002:7f00:1::1'];
        yield 'ipv4-mapped loopback' => ['::ffff:127.0.0.1'];
        yield 'ipv4-mapped metadata' => ['::ffff:a9fe:a9fe'];
        yield 'nat64 private' => ['64:ff9b::10.0.0.1'];
        yield 'not an address' => ['example.com'];
    }

    #[DataProvider('privateOrReserved')]
    public function testNotPublic(string $address): void
    {
        self::assertFalse(PublicAddress::isPublic($address));
    }

    /** @return iterable<string, array{string}> */
    public static function publicAddresses(): iterable
    {
        yield 'ipv4' => ['93.184.216.34'];
        yield 'edge of 172.16/12' => ['172.32.0.1'];
        yield 'edge of 100.64/10' => ['100.128.0.1'];
        yield 'ipv6' => ['2606:2800:220:1:248:1893:25c8:1946'];
        yield 'ipv4-mapped public' => ['::ffff:93.184.216.34'];
    }

    #[DataProvider('publicAddresses')]
    public function testPublic(string $address): void
    {
        self::assertTrue(PublicAddress::isPublic($address));
    }
}
