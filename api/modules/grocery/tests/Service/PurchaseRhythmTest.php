<?php

namespace Maggie\Grocery\Tests\Service;

use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Service\PurchaseRhythm;
use PHPUnit\Framework\TestCase;

/** The rhythm a product is bought at, read from its purchases (MAG-495). */
class PurchaseRhythmTest extends TestCase
{
    /** @return array{day: \DateTimeImmutable, quantity: ?float, unit: ?Unit} */
    private static function bought(string $day, ?float $quantity = 1.0, ?Unit $unit = Unit::Pack): array
    {
        return ['day' => new \DateTimeImmutable($day, new \DateTimeZone('UTC')), 'quantity' => $quantity, 'unit' => $unit];
    }

    public function testThreePurchasesTwoWeeksApartAreDueTwoWeeksAfterTheLast(): void
    {
        $rhythm = PurchaseRhythm::of([self::bought('2026-09-01'), self::bought('2026-09-15'), self::bought('2026-09-29')]);

        self::assertNotNull($rhythm);
        self::assertSame(14, $rhythm->everyDays);
        self::assertSame('2026-09-29', $rhythm->lastBoughtOn->format('Y-m-d'));
        self::assertSame('2026-10-13', $rhythm->dueOn()->format('Y-m-d'));
        self::assertTrue($rhythm->isDueOn(new \DateTimeImmutable('2026-10-13')));
        self::assertFalse($rhythm->isDueOn(new \DateTimeImmutable('2026-10-12')));
    }

    public function testTwoPurchasesAreNoRhythm(): void
    {
        self::assertNull(PurchaseRhythm::of([self::bought('2026-09-01'), self::bought('2026-09-15')]));
    }

    public function testTwoErrandsTheSameDayAreOnePurchase(): void
    {
        $rhythm = PurchaseRhythm::of([
            self::bought('2026-09-01', 1.0),
            self::bought('2026-09-01', 2.0),
            self::bought('2026-09-15'),
        ]);

        self::assertNull($rhythm, 'Two days of purchases are no rhythm, however many errands they took.');
    }

    public function testTheIntervalIsTheMedianGapNotTheMean(): void
    {
        // Gaps 7, 7, 30: the mean (14.7) would be thrown off by one late errand.
        $rhythm = PurchaseRhythm::of([
            self::bought('2026-08-01'),
            self::bought('2026-08-08'),
            self::bought('2026-08-15'),
            self::bought('2026-09-14'),
        ]);

        self::assertSame(7, $rhythm?->everyDays);
    }

    public function testAnEvenNumberOfGapsTakesTheMiddleTwo(): void
    {
        // Gaps 10 and 14: the median is 12.
        $rhythm = PurchaseRhythm::of([self::bought('2026-09-01'), self::bought('2026-09-11'), self::bought('2026-09-25')]);

        self::assertSame(12, $rhythm?->everyDays);
    }

    public function testTheUsualQuantityIsTheMedianInTheLastUnit(): void
    {
        $rhythm = PurchaseRhythm::of([
            self::bought('2026-09-01', 500.0, Unit::Gram),
            self::bought('2026-09-08', 1.0),
            self::bought('2026-09-15', 3.0),
            self::bought('2026-09-22', 2.0),
            self::bought('2026-09-29', 2.0),
        ]);

        self::assertSame(Unit::Pack, $rhythm?->usualUnit);
        self::assertSame(2.0, $rhythm->usualQuantity);
    }

    public function testTheQuantitiesOfOneDayAddUp(): void
    {
        $rhythm = PurchaseRhythm::of([
            self::bought('2026-09-01', 1.0),
            self::bought('2026-09-01', 1.0),
            self::bought('2026-09-15', 2.0),
            self::bought('2026-09-29', 2.0),
        ]);

        self::assertSame(2.0, $rhythm?->usualQuantity);
    }

    public function testNoQuantityLeavesTheUsualQuantityUnknown(): void
    {
        $rhythm = PurchaseRhythm::of([self::bought('2026-09-01', null, null), self::bought('2026-09-15', null, null), self::bought('2026-09-29', null, null)]);

        self::assertNotNull($rhythm);
        self::assertNull($rhythm->usualQuantity);
        self::assertNull($rhythm->usualUnit);
    }

    public function testThePurchasesMayComeInAnyOrder(): void
    {
        $rhythm = PurchaseRhythm::of([self::bought('2026-09-29'), self::bought('2026-09-01'), self::bought('2026-09-15')]);

        self::assertSame('2026-09-29', $rhythm?->lastBoughtOn->format('Y-m-d'));
        self::assertSame(14, $rhythm->everyDays);
    }
}
