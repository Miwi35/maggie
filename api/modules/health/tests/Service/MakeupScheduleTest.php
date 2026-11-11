<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Service\MakeupSchedule;
use PHPUnit\Framework\TestCase;

/** Tonight at the next quarter hour, only if it ends by 23:00; tomorrow at the same hour (MAG-771). */
final class MakeupScheduleTest extends TestCase
{
    public function testSofterAndShorterStartAtTheNextQuarterHour(): void
    {
        $schedule = new MakeupSchedule();

        self::assertEquals(new \DateTimeImmutable('2026-11-10T20:00:00+01:00'), $schedule->start($this->makeup(), MakeupOption::Shorter, new \DateTimeImmutable('2026-11-10T19:46:00+01:00')));
        self::assertEquals(new \DateTimeImmutable('2026-11-10T19:45:00+01:00'), $schedule->start($this->makeup(), MakeupOption::Softer, new \DateTimeImmutable('2026-11-10T19:45:00+01:00')));
        self::assertEquals(new \DateTimeImmutable('2026-11-10T20:00:00+01:00'), $schedule->start($this->makeup(), MakeupOption::Softer, new \DateTimeImmutable('2026-11-10T19:45:01+01:00')));
    }

    public function testTomorrowIsTheSameHourTheNextDay(): void
    {
        self::assertEquals(
            new \DateTimeImmutable('2026-11-11T18:30:00+01:00'),
            (new MakeupSchedule())->start($this->makeup(), MakeupOption::Tomorrow, new \DateTimeImmutable('2026-11-10T19:46:00+01:00')),
        );
    }

    public function testOnlyWhatEndsBy23IsOfferedTonight(): void
    {
        $schedule = new MakeupSchedule();

        self::assertSame([MakeupOption::Softer, MakeupOption::Shorter, MakeupOption::Tomorrow], $schedule->options($this->makeup(), new \DateTimeImmutable('2026-11-10T22:15:00+01:00')));
        self::assertSame([MakeupOption::Shorter, MakeupOption::Tomorrow], $schedule->options($this->makeup(), new \DateTimeImmutable('2026-11-10T22:30:00+01:00')));
        self::assertSame([MakeupOption::Tomorrow], $schedule->options($this->makeup(), new \DateTimeImmutable('2026-11-10T22:45:00+01:00')));
        // The next morning: tonight is no longer the evening of the miss.
        self::assertSame([MakeupOption::Tomorrow], $schedule->options($this->makeup(), new \DateTimeImmutable('2026-11-11T08:00:00+01:00')));
    }

    private function makeup(): WorkoutMakeup
    {
        $missed = (new Workout())->setStartedAt(new \DateTimeImmutable('2026-11-10T18:30:00+01:00'))->setDurationMinutes(45);

        return new WorkoutMakeup(new User(), $missed, 45, 20, 3);
    }
}
