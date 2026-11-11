<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Specification;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Specification\IsPlannedWorkoutMissed;
use PHPUnit\Framework\TestCase;

/** « Séance manquée » (MAG-771): 18:30 for 45 min is missed from 19:45, unless kept; a make-up is never missed in turn. */
final class IsPlannedWorkoutMissedTest extends TestCase
{
    public function testMissedHalfAnHourAfterItsEnd(): void
    {
        $planned = $this->planned();

        self::assertFalse((new IsPlannedWorkoutMissed(new \DateTimeImmutable('2026-11-10T19:44:59+01:00'), false))->isSatisfiedBy($planned));
        self::assertTrue((new IsPlannedWorkoutMissed(new \DateTimeImmutable('2026-11-10T19:45:00+01:00'), false))->isSatisfiedBy($planned));
    }

    public function testKeptADoneSessionOrAMakeupIsNeverMissed(): void
    {
        $late = new \DateTimeImmutable('2026-11-10T21:00:00+01:00');

        self::assertFalse((new IsPlannedWorkoutMissed($late, true))->isSatisfiedBy($this->planned()));
        self::assertFalse((new IsPlannedWorkoutMissed($late, false))->isSatisfiedBy($this->planned()->setSource(WorkoutSource::Manual)));
        self::assertFalse((new IsPlannedWorkoutMissed($late, false))->isSatisfiedBy($this->planned()->setMakeupOf($this->planned())));
    }

    private function planned(): Workout
    {
        return (new Workout())
            ->setType(WorkoutType::Run)
            ->setTitle('Course')
            ->setStartedAt(new \DateTimeImmutable('2026-11-10T18:30:00+01:00'))
            ->setDurationMinutes(45)
            ->setSource(WorkoutSource::Planned);
    }
}
