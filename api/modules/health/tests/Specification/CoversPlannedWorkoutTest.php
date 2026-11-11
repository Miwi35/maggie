<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Specification;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Specification\CoversPlannedWorkout;
use PHPUnit\Framework\TestCase;

/** « Séance tenue » (MAG-771): same local day, the earliest not kept yet, one at most. */
final class CoversPlannedWorkoutTest extends TestCase
{
    private User $user;

    protected function setUp(): void
    {
        $this->user = (new User())->setEmail('spec@example.com');
    }

    public function testACreatedSessionDoneKeepsAPlannedOneOfItsDayWhateverTheHours(): void
    {
        $done = $this->workout('2026-11-10T07:00:00+01:00', WorkoutSource::Watch);
        $spec = new CoversPlannedWorkout(['source' => [null, WorkoutSource::Watch]]);

        self::assertTrue($spec->isTransition($done));
        self::assertTrue($spec->isSatisfiedBy($done, $this->workout('2026-11-10T18:30:00+01:00')));
    }

    public function testTheDayIsReadInTheOffsetOfTheSessionDone(): void
    {
        // 23:30 in Paris is already the 11th in UTC: still the 10th for a session done in Paris.
        $done = $this->workout('2026-11-10T07:00:00+01:00', WorkoutSource::Manual);

        self::assertTrue((new CoversPlannedWorkout([]))->isSatisfiedBy($done, $this->workout('2026-11-10T22:30:00+00:00')));
        self::assertFalse((new CoversPlannedWorkout([]))->isSatisfiedBy($done, $this->workout('2026-11-10T23:30:00+00:00')));
    }

    public function testAnotherDayADoneSessionAnotherUserOrAPlannedSessionDoneKeepNothing(): void
    {
        $done = $this->workout('2026-11-10T07:00:00+01:00', WorkoutSource::Manual);
        $spec = new CoversPlannedWorkout([]);

        self::assertFalse($spec->isSatisfiedBy($done, $this->workout('2026-11-11T07:00:00+01:00')));
        self::assertFalse($spec->isSatisfiedBy($done, $this->workout('2026-11-10T18:30:00+01:00', WorkoutSource::Manual)));
        self::assertFalse($spec->isSatisfiedBy($this->workout('2026-11-10T07:00:00+01:00'), $this->workout('2026-11-10T18:30:00+01:00')));
        $other = $this->workout('2026-11-10T18:30:00+01:00')->setUser((new User())->setEmail('other@example.com'));
        self::assertFalse($spec->isSatisfiedBy($done, $other));
    }

    public function testASessionThatAlreadyKeepsOneKeepsNoOther(): void
    {
        $done = $this->workout('2026-11-10T07:00:00+01:00', WorkoutSource::Manual)->setKeeps($this->workout('2026-11-10T06:00:00+01:00'));

        self::assertFalse((new CoversPlannedWorkout(['startedAt' => [null, $done->getStartedAt()]]))->isTransition($done));
        self::assertFalse((new CoversPlannedWorkout([]))->isSatisfiedBy($done, $this->workout('2026-11-10T18:30:00+01:00')));
    }

    public function testCreatedMovedOrTurnedDoneIsATransitionAReSaveIsNot(): void
    {
        $done = $this->workout('2026-11-10T07:00:00+01:00', WorkoutSource::Manual);

        self::assertTrue((new CoversPlannedWorkout(['source' => ['planned', WorkoutSource::Manual]]))->isTransition($done));
        self::assertTrue((new CoversPlannedWorkout(['startedAt' => [new \DateTimeImmutable(), $done->getStartedAt()]]))->isTransition($done));
        self::assertFalse((new CoversPlannedWorkout(['notes' => ['a', 'b']]))->isTransition($done));
        self::assertFalse((new CoversPlannedWorkout(['source' => ['watch', WorkoutSource::Manual]]))->isTransition($done));
        self::assertFalse((new CoversPlannedWorkout(['source' => [null, WorkoutSource::Planned]]))->isTransition($this->workout('2026-11-10T07:00:00+01:00')));
    }

    public function testItPicksTheEarliestPlannedSessionSatisfied(): void
    {
        $done = $this->workout('2026-11-10T20:00:00+01:00', WorkoutSource::Manual);
        $morning = $this->workout('2026-11-10T07:00:00+01:00');
        $evening = $this->workout('2026-11-10T18:30:00+01:00');

        self::assertSame($morning, (new CoversPlannedWorkout([]))->pick($done, [$this->workout('2026-11-09T07:00:00+01:00'), $morning, $evening]));
        self::assertNull((new CoversPlannedWorkout([]))->pick($done, []));
    }

    private function workout(string $startedAt, WorkoutSource $source = WorkoutSource::Planned): Workout
    {
        return (new Workout())
            ->setUser($this->user)
            ->setType(WorkoutType::Run)
            ->setTitle('Course')
            ->setStartedAt(new \DateTimeImmutable($startedAt))
            ->setDurationMinutes(45)
            ->setSource($source);
    }
}
