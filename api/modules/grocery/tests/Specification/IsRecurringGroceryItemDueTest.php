<?php

namespace Maggie\Grocery\Tests\Specification;

use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\RecurringFrequency;
use Maggie\Grocery\Specification\IsRecurringGroceryItemDue;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class IsRecurringGroceryItemDueTest extends TestCase
{
    /** @return iterable<string, array{RecurringFrequency, ?string, bool}> lastAddedAt is given relative to 2026-10-09 */
    public static function items(): iterable
    {
        yield 'never added' => [RecurringFrequency::Weekly, null, true];
        yield 'weekly, added today' => [RecurringFrequency::Weekly, '2026-10-09', false];
        yield 'weekly, added six days ago' => [RecurringFrequency::Weekly, '2026-10-03', false];
        yield 'weekly, added seven days ago' => [RecurringFrequency::Weekly, '2026-10-02', true];
        yield 'weekly, added eight days ago' => [RecurringFrequency::Weekly, '2026-10-01', true];
        yield 'biweekly, added ten days ago' => [RecurringFrequency::Biweekly, '2026-09-29', false];
        yield 'biweekly, added fourteen days ago' => [RecurringFrequency::Biweekly, '2026-09-25', true];
        yield 'monthly, added three days ago' => [RecurringFrequency::Monthly, '2026-10-06', false];
        yield 'monthly, added a day short of the month' => [RecurringFrequency::Monthly, '2026-09-10', false];
        yield 'monthly, added a month ago' => [RecurringFrequency::Monthly, '2026-09-09', true];
        yield 'monthly, added two months ago' => [RecurringFrequency::Monthly, '2026-08-09', true];
    }

    #[DataProvider('items')]
    public function testDueOnlyOnceThePeriodHasRunOutSinceTheLastAddition(RecurringFrequency $frequency, ?string $lastAddedAt, bool $expected): void
    {
        $item = (new RecurringGroceryItem())
            ->setFrequency($frequency)
            ->setLastAddedAt(null === $lastAddedAt ? null : new \DateTimeImmutable($lastAddedAt));

        $specification = new IsRecurringGroceryItemDue(new \DateTimeImmutable('2026-10-09'));

        self::assertSame($expected, $specification->isSatisfiedBy($item));
    }

    public function testTheTimeOfDayDoesNotMatter(): void
    {
        $item = (new RecurringGroceryItem())
            ->setFrequency(RecurringFrequency::Weekly)
            ->setLastAddedAt(new \DateTimeImmutable('2026-10-02'));

        self::assertTrue((new IsRecurringGroceryItemDue(new \DateTimeImmutable('2026-10-09 06:00')))->isSatisfiedBy($item));
    }

    public function testTheDayOfTheLastAdditionIsACalendarDayWhateverTheZoneItWasReadIn(): void
    {
        // Read back from the database in UTC, asked on a Paris day: exactly a week apart.
        $item = (new RecurringGroceryItem())
            ->setFrequency(RecurringFrequency::Weekly)
            ->setLastAddedAt(new \DateTimeImmutable('2026-10-02', new \DateTimeZone('UTC')));

        $today = new \DateTimeImmutable('2026-10-09', new \DateTimeZone('Europe/Paris'));

        self::assertTrue((new IsRecurringGroceryItemDue($today))->isSatisfiedBy($item));
    }
}
