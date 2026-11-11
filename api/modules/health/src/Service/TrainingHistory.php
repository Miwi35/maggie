<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Dto\ExerciseSession;
use Maggie\Health\Dto\TrainingContext;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\ExerciseFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutVariant;
use Maggie\Health\Repository\WorkoutRepository;
use Psr\Clock\ClockInterface;

/**
 * Reads a user's strength sessions done and turns them into what the
 * progression rules need (MAG-776): the history of one exercise, the context
 * of the program, the training status of a day. Only the user's own sessions.
 */
final class TrainingHistory
{
    private const ACTIVE_DAYS = 28;

    public function __construct(
        private readonly WorkoutRepository $workouts,
        private readonly ExerciseCatalog $catalog,
        private readonly ProgressionRules $rules,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * The sessions done of one exercise, newest first.
     *
     * @return list<ExerciseSession>
     */
    public function forExercise(User $user, string $slug): array
    {
        return $this->sessions($this->workouts->findDoneStrength($user))[$slug] ?? [];
    }

    public function context(User $user): TrainingContext
    {
        return $this->contextOf($this->workouts->findDoneStrength($user), $this->clock->now());
    }

    /**
     * @return array{date: string, programWeek: int|null, phase: string, deload: array{due: bool, reason: string|null, weeksSinceLast: int|null}, stagnating: list<string>, week: array{planned: int, done: int}, hardSetsByPattern: array<string, int>}
     *
     * @throws \InvalidArgumentException when the date is not YYYY-MM-DD
     */
    public function status(User $user, string $date): array
    {
        $day = DailyEnergyReader::day('date', $date);
        $end = $day->modify('+1 day');
        $now = $this->clock->now();
        $asOf = $now < $end ? $now : $end;

        $done = $this->workouts->findDoneStrength($user, $end);
        $context = $this->contextOf($done, $asOf, $day);
        $weekStart = $day->modify('monday this week');
        $week = $this->workouts->findStrengthInRange($user, $weekStart, $weekStart->modify('+7 days'));

        $stagnating = [];
        foreach ($this->sessions($done) as $slug => $history) {
            if (self::days($history[0]->at, $asOf) < self::ACTIVE_DAYS && $this->rules->isStagnating($history, $asOf)) {
                $stagnating[] = $slug;
            }
        }
        sort($stagnating);

        $weekDone = array_values(array_filter($done, static fn (Workout $w): bool => $w->getStartedAt() >= $weekStart));
        $hardSessions = \count(array_filter($weekDone, static fn (Workout $w): bool => ($w->getDifficulty() ?? 0) >= ProgressionRules::HARD_DIFFICULTY));
        $programStart = $this->programStart($done);
        $lastDeload = null;
        foreach ($done as $workout) {
            if (WorkoutVariant::Deload === $workout->getVariant()) {
                $lastDeload = $workout->getStartedAt();
                break;
            }
        }
        $deloadThisWeek = null !== $lastDeload && $lastDeload >= $weekStart;

        return [
            'date' => $date,
            'programWeek' => $context->programWeek,
            'phase' => ProgressionRules::phase($context->programWeek),
            'deload' => ProgressionRules::deload($programStart, $lastDeload, $asOf, \count($stagnating), $hardSessions, $deloadThisWeek),
            'stagnating' => $stagnating,
            'week' => [
                'planned' => \count(array_filter($week, static fn (Workout $w): bool => WorkoutSource::Planned === $w->getSource())),
                'done' => \count(array_filter($week, static fn (Workout $w): bool => WorkoutSource::Planned !== $w->getSource())),
            ],
            'hardSetsByPattern' => $this->hardSets($weekDone),
        ];
    }

    /**
     * @param list<Workout> $done newest first
     */
    private function contextOf(array $done, \DateTimeImmutable $now, ?\DateTimeImmutable $at = null): TrainingContext
    {
        $difficulties = [];
        foreach ($done as $workout) {
            if (null !== $workout->getDifficulty()) {
                $difficulties[] = ['id' => (string) $workout->getId(), 'difficulty' => $workout->getDifficulty()];
            }
        }

        $calibration = null;
        $calibrationId = null;
        foreach ($done as $workout) {
            if (WorkoutVariant::Calibration === $workout->getVariant()) {
                $calibration = [];
                foreach ($this->sessionsOf($workout) as $slug => $session) {
                    if ([] !== $session->counts) {
                        $calibration[$slug] = max($session->counts);
                    }
                }
                $calibrationId = (string) $workout->getId();
                break;
            }
        }

        return new TrainingContext(
            $now,
            ProgressionRules::programWeek($this->programStart($done), $at ?? $now),
            $difficulties,
            $calibration,
            $calibrationId,
        );
    }

    /**
     * @param list<Workout> $done newest first
     */
    private function programStart(array $done): ?\DateTimeImmutable
    {
        $start = null;
        foreach ($done as $workout) {
            if (WorkoutVariant::Calibration === $workout->getVariant()) {
                $start = $workout->getStartedAt();
            }
        }

        return $start;
    }

    /**
     * @param list<Workout> $done newest first
     *
     * @return array<string, list<ExerciseSession>> per exercise slug, newest first
     */
    private function sessions(array $done): array
    {
        $bySlug = [];
        foreach ($done as $workout) {
            foreach ($this->sessionsOf($workout) as $slug => $session) {
                $bySlug[$slug][] = $session;
            }
        }

        return $bySlug;
    }

    /** @return array<string, ExerciseSession> the exercises with at least one set done */
    private function sessionsOf(Workout $workout): array
    {
        $sessions = [];
        foreach ($workout->getExercises() as $item) {
            /** @var list<array{reps?: int, seconds?: int, meters?: int, loadKg?: float}> $sets */
            $sets = $item['done'];
            $counts = [];
            $load = $item['loadKg'];
            foreach ($sets as $set) {
                $counts[] = $set['reps'] ?? $set['seconds'] ?? $set['meters'] ?? 0;
                $load = $set['loadKg'] ?? $load;
            }
            if ([] === $counts) {
                continue;
            }
            $sessions[$item['exercise']] = new ExerciseSession(
                (string) $workout->getId(),
                $workout->getStartedAt(),
                $workout->getVariant(),
                (int) $item['step'],
                (int) $item['sets'],
                $load,
                (bool) $item['tempo'],
                null === $item['feeling'] ? null : ExerciseFeeling::from($item['feeling']),
                $counts,
            );
        }

        return $sessions;
    }

    /**
     * Sets done close enough to failure to count as hard: no target RIR given,
     * or 3 reps in reserve at most.
     *
     * @param list<Workout> $week
     *
     * @return array<string, int>
     */
    private function hardSets(array $week): array
    {
        $byPattern = [];
        foreach ($week as $workout) {
            foreach ($workout->getExercises() as $item) {
                $rir = $item['targetRir'];
                $count = \count($item['done']);
                $pattern = $this->catalog->exercise($item['exercise'])['pattern'] ?? null;
                if (0 === $count || null === $pattern || (null !== $rir && $rir > 3)) {
                    continue;
                }
                $byPattern[$pattern] = ($byPattern[$pattern] ?? 0) + $count;
            }
        }
        ksort($byPattern);

        return $byPattern;
    }

    private static function days(\DateTimeImmutable $from, \DateTimeImmutable $to): int
    {
        return (int) $from->diff($to)->format('%r%a');
    }
}
