<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Exception\InvalidTaskScheduleException;
use Maggie\Calendar\Service\TaskRecurrence;
use PHPUnit\Framework\TestCase;

/** The rule of a recurring task, checked on every door and unfolded by days (MAG-870). Tuesday 2026-11-24 is the first day. */
final class TaskRecurrenceTest extends TestCase
{
    private const EVERY_OTHER_TUESDAY = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU';

    public function testEveryOtherTuesdayComesBackFourteenDaysLater(): void
    {
        $task = self::plannedOn('2026-11-24');

        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);

        self::assertSame(self::EVERY_OTHER_TUESDAY, $task->getRrule());
        self::assertSame('2026-11-24', $task->getRecurrenceDate()?->format('Y-m-d'));
        self::assertSame('2026-12-08', TaskRecurrence::nextDay($task, self::day('2026-11-24'))?->format('Y-m-d'));
    }

    public function testTheDaysAlreadyGoneAreSkipped(): void
    {
        $task = self::plannedOn('2026-11-24');
        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);

        self::assertSame('2026-12-22', TaskRecurrence::nextDay($task, self::day('2026-12-20'))?->format('Y-m-d'));
        self::assertSame('2026-12-08', TaskRecurrence::nextDay($task, self::day('2026-12-08'))?->format('Y-m-d'), 'today is still a day to come');
    }

    public function testAnUntilReachedEndsTheSeries(): void
    {
        $task = self::plannedOn('2026-11-24');
        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY.';UNTIL=20261201');

        self::assertNull(TaskRecurrence::nextDay($task, self::day('2026-11-24')));
    }

    public function testADayOutsideTheRuleIsRefusedWithTheFirstDayOfTheRule(): void
    {
        $task = self::plannedOn('2026-11-25');

        try {
            TaskRecurrence::apply($task, 'FREQ=WEEKLY;BYDAY=TU');
            self::fail('A Wednesday is not a day of a Tuesday rule.');
        } catch (InvalidTaskScheduleException $e) {
            self::assertStringContainsString('2026-11-25 (Wednesday) is not a day of rrule', $e->getMessage());
            self::assertStringContainsString('2026-12-01 (Tuesday)', $e->getMessage());
        }
        self::assertNull($task->getRrule());
    }

    public function testCountIsRefusedAndUntilIsNamed(): void
    {
        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('UNTIL');

        TaskRecurrence::apply(self::plannedOn('2026-11-24'), 'FREQ=WEEKLY;BYDAY=TU;COUNT=5');
    }

    public function testARuleFinerThanADayIsRefused(): void
    {
        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('DAILY, WEEKLY, MONTHLY or YEARLY');

        TaskRecurrence::apply(self::plannedOn('2026-11-24'), 'FREQ=HOURLY');
    }

    public function testAPrefixedRuleIsRefused(): void
    {
        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('without the "RRULE:" prefix');

        TaskRecurrence::apply(self::plannedOn('2026-11-24'), 'RRULE:'.self::EVERY_OTHER_TUESDAY);
    }

    public function testAnUnreadableRuleIsRefused(): void
    {
        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('cannot be read');

        TaskRecurrence::apply(self::plannedOn('2026-11-24'), 'tous les mardis');
    }

    public function testATaskWithoutADayCannotRepeat(): void
    {
        $task = (new Task())->setTitle('Sortir la poubelle');

        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('This task has neither');

        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);
    }

    public function testASubtaskCannotRepeat(): void
    {
        $task = self::plannedOn('2026-11-24')->setParent((new Task())->setTitle('Ménage'));

        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('A subtask cannot repeat: this task is a subtask of « Ménage »');

        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);
    }

    public function testTheDueDayAnchorsATaskWithoutAPlannedDay(): void
    {
        $task = (new Task())->setTitle('Payer le loyer')->setDueDate(self::day('2026-12-05'));

        TaskRecurrence::apply($task, 'FREQ=MONTHLY;BYMONTHDAY=5');

        self::assertSame('2027-01-05', TaskRecurrence::nextDay($task, self::day('2026-12-01'))?->format('Y-m-d'));
    }

    public function testAMovedDayKeepsTheSeriesDayAndLosingBothDaysIsRefused(): void
    {
        $task = self::plannedOn('2026-11-24');
        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);

        $task->setScheduledDate(self::day('2026-11-25'));
        TaskRecurrence::apply($task);
        self::assertSame('2026-11-24', $task->getRecurrenceDate()?->format('Y-m-d'));

        $task->setScheduledDate(null);
        $this->expectException(InvalidTaskScheduleException::class);
        TaskRecurrence::apply($task);
    }

    public function testADoneTaskCannotTakeARule(): void
    {
        $task = self::plannedOn('2026-11-24')->setCompletedAt(new \DateTimeImmutable('2026-11-24 09:00'));

        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('A done task cannot take a rrule');

        TaskRecurrence::apply($task, self::EVERY_OTHER_TUESDAY);
    }

    public function testARuleLongerThanTheColumnIsRefused(): void
    {
        $this->expectException(InvalidTaskScheduleException::class);
        $this->expectExceptionMessage('at most 255 characters');

        TaskRecurrence::apply(self::plannedOn('2026-11-24'), self::EVERY_OTHER_TUESDAY.';BYMONTH='.str_repeat('1,', 130).'1');
    }

    private static function plannedOn(string $day): Task
    {
        return (new Task())->setTitle('Sortir la poubelle jaune')->setScheduledDate(self::day($day));
    }

    private static function day(string $day): \DateTimeImmutable
    {
        return new \DateTimeImmutable($day, new \DateTimeZone('UTC'));
    }
}
