<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Service\SeasonalAmounts;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class SeasonalAmountsTest extends TestCase
{
    public function testASeasonCrossesTheNewYear(): void
    {
        self::assertSame([11, 12, 1, 2], SeasonalAmounts::months(['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500]));
    }

    public function testAOneMonthSeasonCoversThatMonthOnly(): void
    {
        self::assertSame([7], SeasonalAmounts::months(['fromMonth' => 7, 'toMonth' => 7, 'amountCents' => 0]));
    }

    public function testCoveringFindsTheSeasonOfAMonth(): void
    {
        $seasons = [
            ['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500],
            ['fromMonth' => 7, 'toMonth' => 8, 'amountCents' => 1000],
        ];

        self::assertSame(4500, SeasonalAmounts::covering($seasons, 1)['amountCents'] ?? null);
        self::assertSame(1000, SeasonalAmounts::covering($seasons, 8)['amountCents'] ?? null);
        self::assertNull(SeasonalAmounts::covering($seasons, 3));
    }

    public function testNormalizeKeepsTheThreeFieldsOnly(): void
    {
        self::assertSame(
            [['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500]],
            SeasonalAmounts::normalize([['amountCents' => 4500, 'toMonth' => 2, 'fromMonth' => 11, 'label' => 'hiver']]),
        );
    }

    /** @return iterable<string, array{mixed}> */
    public static function invalidSeasons(): iterable
    {
        yield 'not a list' => [['winter' => ['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500]]];
        yield 'missing amount' => [[['fromMonth' => 11, 'toMonth' => 2]]];
        yield 'month as a string' => [[['fromMonth' => '11', 'toMonth' => 2, 'amountCents' => 4500]]];
        yield 'month 13' => [[['fromMonth' => 11, 'toMonth' => 13, 'amountCents' => 4500]]];
        yield 'month 0' => [[['fromMonth' => 0, 'toMonth' => 2, 'amountCents' => 4500]]];
        yield 'negative amount' => [[['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => -1]]];
        yield 'overlap across the new year' => [[
            ['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500],
            ['fromMonth' => 2, 'toMonth' => 3, 'amountCents' => 3000],
        ]];
    }

    #[DataProvider('invalidSeasons')]
    public function testNormalizeRefusesWhatIsNotASeason(mixed $seasons): void
    {
        $this->expectException(\InvalidArgumentException::class);

        SeasonalAmounts::normalize($seasons);
    }
}
