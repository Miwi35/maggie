<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Service\ActivityTitle;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The title of the monthly review's occurrences (MAG-456). */
class ActivityTitleTest extends TestCase
{
    /** @return iterable<string, array{string, string}> */
    public static function months(): iterable
    {
        yield 'janvier' => ['2027-01-25', 'Revue de janvier'];
        yield 'avril, elided' => ['2027-04-25', "Revue d'avril"];
        yield 'août, elided' => ['2027-08-25', "Revue d'août"];
        yield 'octobre, elided' => ['2026-10-25', "Revue d'octobre"];
        yield 'novembre' => ['2026-11-25', 'Revue de novembre'];
        yield 'décembre' => ['2026-12-25', 'Revue de décembre'];
    }

    #[DataProvider('months')]
    public function testTheReviewIsNamedAfterItsMonth(string $day, string $title): void
    {
        self::assertSame($title, ActivityTitle::review(new \DateTimeImmutable($day)));
    }

    public function testTheMonthIsReadInTheEventsOwnZone(): void
    {
        $event = (new Event())->setSummary('Revue du mois')->setTimeZone('Europe/Paris')->setActivity(Event::ACTIVITY_FINANCE_REVIEW);
        // 31 October 23:30 UTC is already the 1st of November in Paris.
        $event->scheduleTimed(new \DateTimeImmutable('2026-10-31T23:30:00Z'), new \DateTimeImmutable('2026-11-01T00:30:00Z'));

        self::assertSame('Revue de novembre', ActivityTitle::ofOccurrence($event));
    }

    public function testAnEventWithoutActivityHasNoTitleOfItsOwn(): void
    {
        $event = (new Event())->setSummary('Dentiste');
        $event->scheduleTimed(new \DateTimeImmutable('2026-10-25T20:00:00Z'), new \DateTimeImmutable('2026-10-25T21:00:00Z'));

        self::assertNull(ActivityTitle::ofOccurrence($event));
    }
}
