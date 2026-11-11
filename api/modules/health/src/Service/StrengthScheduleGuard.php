<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Exception\WorkoutScheduleException;
use Maggie\Health\Repository\WorkoutRepository;

/**
 * The rules of the week for a planned strength session (MAG-776), checked when
 * it is written, planned or done sessions alike counting as the others:
 * 3 sessions a week at most (Monday to Sunday, Europe/Paris), 24 h between two
 * starts, 2 sessions a week with an eccentric exercise at most, 48 h between
 * them. Nothing is moved: the session is refused.
 */
class StrengthScheduleGuard
{
    public const MAX_PER_WEEK = 3;
    public const MAX_ECCENTRIC_PER_WEEK = 2;
    public const MIN_HOURS = 24;
    public const MIN_ECCENTRIC_HOURS = 48;

    public function __construct(
        private readonly WorkoutRepository $workouts,
        private readonly UserRepository $users,
        private readonly ExerciseCatalog $catalog,
    ) {
    }

    /**
     * @param list<WorkoutExerciseInput> $exercises
     *
     * @throws WorkoutScheduleException
     */
    public function assert(string $userId, ?string $exceptId, \DateTimeImmutable $startedAt, array $exercises): void
    {
        $user = $this->users->find($userId);
        if (null === $user) {
            return;
        }
        $eccentric = [] !== array_filter($exercises, fn (WorkoutExerciseInput $e): bool => $this->isEccentric($e->exercise));

        $local = $startedAt->setTimezone(new \DateTimeZone(ProgressionRules::TIME_ZONE));
        $weekStart = $local->modify('monday this week')->setTime(0, 0);
        $weekEnd = $weekStart->modify('+7 days');
        $others = $this->workouts->findStrengthInRange($user, $weekStart->modify('-48 hours'), $weekEnd->modify('+48 hours'), $exceptId);

        $week = array_values(array_filter($others, static fn (Workout $w): bool => $w->getStartedAt() >= $weekStart && $w->getStartedAt() < $weekEnd));
        $current = array_map(fn (Workout $w): array => [
            'id' => (string) $w->getId(),
            'title' => $w->getTitle(),
            'startedAt' => $w->getStartedAt()->format(\DATE_ATOM),
            'source' => $w->getSource()->value,
            'eccentric' => $this->hasEccentric($w),
        ], $week);

        if (\count($week) >= self::MAX_PER_WEEK) {
            throw new WorkoutScheduleException('startedAt', \sprintf('at most %d strength sessions a week: this week already has %d.', self::MAX_PER_WEEK, \count($week)), $current);
        }
        foreach ($others as $other) {
            if (self::hoursBetween($other->getStartedAt(), $startedAt) < self::MIN_HOURS) {
                throw new WorkoutScheduleException('startedAt', \sprintf('at least %d h between two strength sessions: %s is too close.', self::MIN_HOURS, $other->getStartedAt()->format(\DATE_ATOM)), $current);
            }
        }
        if (!$eccentric) {
            return;
        }
        if (\count(array_filter($week, $this->hasEccentric(...))) >= self::MAX_ECCENTRIC_PER_WEEK) {
            throw new WorkoutScheduleException('exercises', \sprintf('at most %d strength sessions a week with an eccentric exercise.', self::MAX_ECCENTRIC_PER_WEEK), $current);
        }
        foreach ($others as $other) {
            if ($this->hasEccentric($other) && self::hoursBetween($other->getStartedAt(), $startedAt) < self::MIN_ECCENTRIC_HOURS) {
                throw new WorkoutScheduleException('exercises', \sprintf('at least %d h between two sessions with an eccentric exercise: %s is too close.', self::MIN_ECCENTRIC_HOURS, $other->getStartedAt()->format(\DATE_ATOM)), $current);
            }
        }
    }

    private function hasEccentric(Workout $workout): bool
    {
        foreach ($workout->exerciseList() as $item) {
            if ($this->isEccentric($item->getExercise())) {
                return true;
            }
        }

        return false;
    }

    private function isEccentric(string $slug): bool
    {
        return true === ($this->catalog->exercise($slug)['eccentric'] ?? false);
    }

    private static function hoursBetween(\DateTimeImmutable $a, \DateTimeImmutable $b): float
    {
        return abs($a->getTimestamp() - $b->getTimestamp()) / 3600;
    }
}
