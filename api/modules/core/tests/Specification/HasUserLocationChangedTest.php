<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Specification;

use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Specification\HasUserLocationChanged;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

final class HasUserLocationChangedTest extends TestCase
{
    public static function changeSets(): iterable
    {
        $at = new \DateTimeImmutable('2026-10-26T08:00:00Z');

        yield 'a creation moves the position' => [['gpsLatitude' => [null, 48.8], 'gpsCapturedAt' => [null, $at]], 'position', true];
        yield 'a creation by the car leaves the position unset' => [['gpsLatitude' => [null, null], 'carLatitude' => [null, 48.8]], 'position', false];
        yield 'a creation by the car parks it' => [['gpsLatitude' => [null, null], 'carLatitude' => [null, 48.8]], 'car', true];
        yield 'a new latitude' => [['gpsLatitude' => [48.8, 48.9]], 'position', true];
        yield 'a new accuracy alone' => [['gpsAccuracy' => [10.0, 30.0]], 'position', true];
        yield 'a later capture of the same place' => [['gpsCapturedAt' => [$at, $at->modify('+1 minute')]], 'position', true];
        yield 'the same instant, another object' => [['gpsCapturedAt' => [$at, new \DateTimeImmutable('2026-10-26T09:00:00+01:00')]], 'position', false];
        yield 'the same car date, another object' => [['carParkedAt' => [$at, new \DateTimeImmutable('2026-10-26T08:00:00Z')]], 'car', false];
        yield 'the position moved, not the car' => [['gpsLatitude' => [48.8, 48.9]], 'car', false];
        yield 'a network fix moves the position' => [['networkLatitude' => [null, 48.8], 'networkCapturedAt' => [null, $at]], 'position', true];
        yield 'a new network accuracy alone' => [['networkAccuracy' => [500.0, 300.0]], 'position', true];
        yield 'a network fix is not the car' => [['networkLatitude' => [48.8, 48.9]], 'car', false];
        yield 'nothing written' => [[], 'position', false];
    }

    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet */
    #[DataProvider('changeSets')]
    public function testItSeesOnlyARealMove(array $changeSet, string $what, bool $expected): void
    {
        self::assertSame($expected, (new HasUserLocationChanged($changeSet, $what))->isSatisfiedBy(new UserLocation()));
    }
}
