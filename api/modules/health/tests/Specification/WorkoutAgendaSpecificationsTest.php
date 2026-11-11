<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Specification;

use Maggie\Calendar\Entity\Event;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Specification\IsPlannedWorkout;
use Maggie\Health\Specification\IsSportEventMoved;
use Maggie\Health\Specification\IsWorkoutNoLongerPlanned;
use PHPUnit\Framework\TestCase;

/** What moves a session in the agenda, and what moves it back (MAG-777). */
class WorkoutAgendaSpecificationsTest extends TestCase
{
    private static function workout(WorkoutSource $source): Workout
    {
        return (new Workout())->setSource($source);
    }

    private static function event(?string $activity): Event
    {
        return (new Event())->setActivity($activity);
    }

    public function testAPlannedSessionCreatedOrMovedIsFiled(): void
    {
        $planned = self::workout(WorkoutSource::Planned);

        self::assertTrue((new IsPlannedWorkout(['title' => [null, 'Séance A'], 'source' => [null, WorkoutSource::Planned]]))->isSatisfiedBy($planned));
        self::assertTrue((new IsPlannedWorkout(['startedAt' => [new \DateTimeImmutable(), new \DateTimeImmutable()]]))->isSatisfiedBy($planned));
        self::assertTrue((new IsPlannedWorkout(['durationMinutes' => [30, 45]]))->isSatisfiedBy($planned));
    }

    public function testAChangeTheEventDoesNotShowOrASessionDoneIsNot(): void
    {
        self::assertFalse((new IsPlannedWorkout(['notes' => [null, 'Sac lesté']]))->isSatisfiedBy(self::workout(WorkoutSource::Planned)));
        self::assertFalse((new IsPlannedWorkout(['event' => [null, new Event()]]))->isSatisfiedBy(self::workout(WorkoutSource::Planned)));
        self::assertFalse((new IsPlannedWorkout(['title' => [null, 'Course']]))->isSatisfiedBy(self::workout(WorkoutSource::Watch)));
    }

    public function testASessionMarkedAsDoneIsNoLongerPlanned(): void
    {
        // Doctrine hands the previous enum over as the column's string.
        self::assertTrue((new IsWorkoutNoLongerPlanned(['source' => ['planned', WorkoutSource::Manual]]))->isSatisfiedBy(self::workout(WorkoutSource::Manual)));
        self::assertTrue((new IsWorkoutNoLongerPlanned(['source' => [WorkoutSource::Planned, WorkoutSource::Watch]]))->isSatisfiedBy(self::workout(WorkoutSource::Watch)));

        self::assertFalse((new IsWorkoutNoLongerPlanned(['source' => [null, WorkoutSource::Manual]]))->isSatisfiedBy(self::workout(WorkoutSource::Manual)));
        self::assertFalse((new IsWorkoutNoLongerPlanned(['source' => ['manual', WorkoutSource::Planned]]))->isSatisfiedBy(self::workout(WorkoutSource::Planned)));
        self::assertFalse((new IsWorkoutNoLongerPlanned(['title' => ['A', 'B']]))->isSatisfiedBy(self::workout(WorkoutSource::Manual)));
    }

    public function testASportEventMovedOrResizedMovesItsSession(): void
    {
        $sport = self::event(Event::ACTIVITY_SPORT);

        self::assertTrue((new IsSportEventMoved(['startAt' => [new \DateTimeImmutable(), new \DateTimeImmutable()]]))->isSatisfiedBy($sport));
        self::assertTrue((new IsSportEventMoved(['endAt' => [new \DateTimeImmutable(), new \DateTimeImmutable()]]))->isSatisfiedBy($sport));

        self::assertFalse((new IsSportEventMoved(['summary' => ['A', 'B']]))->isSatisfiedBy($sport));
        self::assertFalse((new IsSportEventMoved(['startAt' => [new \DateTimeImmutable(), new \DateTimeImmutable()]]))->isSatisfiedBy(self::event(null)));
        self::assertFalse((new IsSportEventMoved(['startAt' => [new \DateTimeImmutable(), new \DateTimeImmutable()]]))->isSatisfiedBy(self::event(Event::ACTIVITY_FINANCE_REVIEW)));
    }
}
