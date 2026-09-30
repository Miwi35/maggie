<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Maggie\Core\E2e\Fixture\E2eDateProvider;
use PHPUnit\Framework\TestCase;

/**
 * The provider is what makes seeded dates both near today and identical on
 * every run, so its arithmetic is worth pinning down.
 */
final class E2eDateProviderTest extends TestCase
{
    public function testDefaultAnchorIsTodayAtMidnightUtc(): void
    {
        $provider = new E2eDateProvider();

        self::assertSame('00:00:00', $provider->getAnchor()->format('H:i:s'));
        self::assertSame('UTC', $provider->getAnchor()->getTimezone()->getName());
        self::assertSame(
            (new \DateTimeImmutable('today midnight', new \DateTimeZone('UTC')))->format('Y-m-d'),
            $provider->getAnchor()->format('Y-m-d'),
        );
    }

    public function testOffsetsAreCountedFromTheAnchor(): void
    {
        $provider = $this->anchoredAt('2026-03-15T00:00:00+00:00');

        self::assertSame('2026-03-17 09:00:00', $provider->e2eDate('+2 days 09:00')->format('Y-m-d H:i:s'));
        self::assertSame('2026-03-14 20:15:00', $provider->e2eDate('-1 day 20:15')->format('Y-m-d H:i:s'));
        self::assertSame('2026-03-01', $provider->e2eDateString('first day of this month'));
        self::assertSame('2026-02-01', $provider->e2eDateString('first day of last month'));
    }

    public function testNoModifierReturnsTheAnchorItself(): void
    {
        $provider = $this->anchoredAt('2026-03-15T00:00:00+00:00');

        self::assertSame('2026-03-15 00:00:00', $provider->e2eDate()->format('Y-m-d H:i:s'));
    }

    public function testYearAndMonthFollowTheAnchor(): void
    {
        $provider = $this->anchoredAt('2026-01-05T00:00:00+00:00');

        self::assertSame(2026, $provider->e2eYear());
        self::assertSame(1, $provider->e2eMonth());
        // Across a year boundary, which is where a naive `-1 month` breaks.
        self::assertSame(2025, $provider->e2eYear('first day of last month'));
        self::assertSame(12, $provider->e2eMonth('first day of last month'));
    }

    public function testTwoCallsWithTheSameAnchorGiveTheSameDate(): void
    {
        $first = $this->anchoredAt('2026-03-15T00:00:00+00:00')->e2eDate('+3 days 18:00');
        $second = $this->anchoredAt('2026-03-15T00:00:00+00:00')->e2eDate('+3 days 18:00');

        self::assertEquals($first, $second);
    }

    public function testAnUnparsableModifierIsRejected(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        // A typo in a fixture file must fail loudly, not silently seed the
        // anchor and leave a journey asserting the wrong day.
        $this->anchoredAt('2026-03-15T00:00:00+00:00')->e2eDate('+2 potatoes');
    }

    private function anchoredAt(string $iso): E2eDateProvider
    {
        $provider = new E2eDateProvider();
        $provider->setAnchor(new \DateTimeImmutable($iso));

        return $provider;
    }
}
