<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Specification\IsTaskOccurrenceOver;
use PHPUnit\Framework\TestCase;

/** When the occurrence of a recurring task hands its rule on (MAG-870): done, or its day gone. */
final class IsTaskOccurrenceOverTest extends TestCase
{
    public function testDoneWithARuleIsOver(): void
    {
        $task = self::recurringOn('2026-11-24')->setCompletedAt(new \DateTimeImmutable('2026-11-24 09:00'));

        self::assertTrue(self::onDay('2026-11-24')->isSatisfiedBy($task));
    }

    public function testOpenWithItsDayGoneIsOver(): void
    {
        self::assertTrue(self::onDay('2026-11-25')->isSatisfiedBy(self::recurringOn('2026-11-24')));
    }

    public function testOpenTodayIsNotOver(): void
    {
        self::assertFalse(self::onDay('2026-11-24')->isSatisfiedBy(self::recurringOn('2026-11-24')));
    }

    public function testWithoutARuleIsNeverOver(): void
    {
        $task = (new Task())->setScheduledDate(new \DateTimeImmutable('2026-11-24'))->setCompletedAt(new \DateTimeImmutable('2026-11-24 09:00'));

        self::assertFalse(self::onDay('2026-11-30')->isSatisfiedBy($task));
    }

    private static function recurringOn(string $day): Task
    {
        $date = new \DateTimeImmutable($day, new \DateTimeZone('UTC'));

        return (new Task())->setScheduledDate($date)->repeat('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU', $date);
    }

    private static function onDay(string $day): IsTaskOccurrenceOver
    {
        return new IsTaskOccurrenceOver(new \DateTimeImmutable($day, new \DateTimeZone('UTC')));
    }
}
