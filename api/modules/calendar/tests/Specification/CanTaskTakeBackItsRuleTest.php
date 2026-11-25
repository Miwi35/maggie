<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Specification\CanTaskTakeBackItsRule;
use PHPUnit\Framework\TestCase;

/** When a reopened occurrence takes its rule back (MAG-1033): the next one it handed it to is untouched. */
final class CanTaskTakeBackItsRuleTest extends TestCase
{
    private const RULE = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU';

    public function testAReopenedOccurrenceWithAnUntouchedNextOneTakesItBack(): void
    {
        self::assertTrue((new CanTaskTakeBackItsRule())->isSatisfiedBy(self::reopened(self::next())));
    }

    public function testAnOccurrenceStillDoneDoesNot(): void
    {
        $task = self::reopened(self::next())->setCompletedAt(new \DateTimeImmutable('2026-11-24 09:00'));

        self::assertFalse((new CanTaskTakeBackItsRule())->isSatisfiedBy($task));
    }

    public function testAnOccurrenceWithARuleDoesNot(): void
    {
        $task = self::reopened(self::next())->setRrule(self::RULE);

        self::assertFalse((new CanTaskTakeBackItsRule())->isSatisfiedBy($task));
    }

    public function testAnOccurrenceWithoutNextOneDoesNot(): void
    {
        self::assertFalse((new CanTaskTakeBackItsRule())->isSatisfiedBy(self::reopened(null)));
    }

    public function testANextOneDoneDoesNot(): void
    {
        $next = self::next()->setCompletedAt(new \DateTimeImmutable('2026-12-08 09:00'));

        self::assertFalse((new CanTaskTakeBackItsRule())->isSatisfiedBy(self::reopened($next)));
    }

    public function testANextOneWithoutRuleDoesNot(): void
    {
        self::assertFalse((new CanTaskTakeBackItsRule())->isSatisfiedBy(self::reopened(self::next()->setRrule(null))));
    }

    private static function next(): Task
    {
        $date = new \DateTimeImmutable('2026-12-08', new \DateTimeZone('UTC'));

        return (new Task())->setScheduledDate($date)->repeat(self::RULE, $date);
    }

    private static function reopened(?Task $next): Task
    {
        $date = new \DateTimeImmutable('2026-11-24', new \DateTimeZone('UTC'));

        return (new Task())->setScheduledDate($date)->repeat(self::RULE, $date)->handOverTo($next);
    }
}
