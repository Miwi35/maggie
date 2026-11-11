<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Dto\ExerciseSession;
use Maggie\Health\Dto\TrainingContext;
use Maggie\Health\Enum\ExerciseFeeling;
use Maggie\Health\Enum\WorkoutVariant;

/**
 * The progression rules of the coach method (MAG-776), pure: no database, the
 * history and the context come in, a prescription and its reason come out.
 *
 * @phpstan-type Prescription array{exercise: string, name: string, unit: string, step: int, sets: int, target: int, targetMin: int, targetMax: int, loadKg: float|null, restSeconds: int, tempo: bool, targetRir: int, cue: string}
 * @phpstan-type Next array{prescription: Prescription, reason: string, basedOn: list<string>}
 */
final class ProgressionRules
{
    public const TIME_ZONE = 'Europe/Paris';
    public const STAGNATION_DAYS = 14;
    public const DELOAD_WEEKS = 8;
    public const LOAD_STEP_KG = 5.0;
    public const START_LOAD_KG = 5.0;
    public const HARD_DIFFICULTY = 9;
    public const EASY_DIFFICULTY = 5;

    public function __construct(private readonly ExerciseCatalog $catalog)
    {
    }

    /**
     * @param list<ExerciseSession> $history the sessions done of this exercise, newest first
     *
     * @return Next
     */
    public function next(string $slug, string $variant, array $history, TrainingContext $ctx): array
    {
        if (null === WorkoutVariant::tryFrom($variant) || WorkoutVariant::Calibration->value === $variant) {
            throw new \InvalidArgumentException(\sprintf('Unknown variant "%s": normal, soft or deload.', $variant));
        }
        [$exercise, $ladder, $step] = $this->locate($slug);

        $base = array_values(array_filter($history, static fn (ExerciseSession $s): bool => WorkoutVariant::Deload !== $s->variant));
        $s0 = $base[0] ?? null;

        if (null === $s0 || WorkoutVariant::Calibration === $s0->variant) {
            return $this->start($exercise, $ladder, $step, $ctx);
        }

        $current = $this->prescription($exercise, $step, $s0->sets, max($s0->lowest(), $step['targetMin']), $s0->loadKg, $s0->tempo, $ctx);

        if ('deload' === $variant) {
            $current['sets'] = max(1, (int) ceil($s0->sets / 2));
            $current['targetRir'] = 3;

            return ['prescription' => $current, 'reason' => 'deload', 'basedOn' => [$s0->workoutId]];
        }

        if ('soft' === $variant) {
            $current['sets'] = max(1, $s0->sets - 1);
            $current['targetRir'] = 3;
            $basedOn = [$s0->workoutId];
            if ($exercise['eccentric']) {
                $previous = $ladder['steps'][$step['position'] - 2] ?? null;
                if (null !== $previous) {
                    $current = $this->prescription($this->exerciseOf($previous['exercise']), $previous, $current['sets'], $previous['targetMin'], $s0->loadKg, false, $ctx);
                    $current['targetRir'] = 3;
                }
            }

            return ['prescription' => $current, 'reason' => 'soft', 'basedOn' => $basedOn];
        }

        return $this->normal($exercise, $ladder, $step, $base, $s0, $current, $ctx);
    }

    /**
     * @param list<ExerciseSession> $history done sessions of one exercise, newest first
     */
    public function isStagnating(array $history, \DateTimeImmutable $now): bool
    {
        return null !== $this->stagnationWindow($history, $now);
    }

    public static function programWeek(?\DateTimeImmutable $start, \DateTimeImmutable $at): ?int
    {
        if (null === $start) {
            return null;
        }
        $days = self::days($start, $at);

        return $days < 0 ? null : intdiv($days, 7) + 1;
    }

    public static function phase(?int $programWeek): string
    {
        return match (true) {
            null === $programWeek || $programWeek <= 2 => 'calibration',
            $programWeek <= 4 => 'ramp_up',
            default => 'normal',
        };
    }

    /**
     * @return array{due: bool, reason: string|null, weeksSinceLast: int|null}
     */
    public static function deload(?\DateTimeImmutable $programStart, ?\DateTimeImmutable $lastDeload, \DateTimeImmutable $now, int $stagnating, int $hardSessionsThisWeek, bool $deloadThisWeek): array
    {
        $reference = $programStart;
        if (null !== $lastDeload && (null === $reference || $lastDeload > $reference)) {
            $reference = $lastDeload;
        }
        $weeks = null === $reference ? null : max(0, intdiv(self::days($reference, $now), 7));

        $reason = match (true) {
            $stagnating >= 2 => 'stagnation',
            $hardSessionsThisWeek >= 2 => 'hard_sessions',
            null !== $weeks && $weeks >= self::DELOAD_WEEKS => 'scheduled',
            default => null,
        };
        if ($deloadThisWeek) {
            $reason = null;
        }

        return ['due' => null !== $reason, 'reason' => $reason, 'weeksSinceLast' => $weeks];
    }

    /**
     * @param array<string, mixed> $exercise
     * @param array<string, mixed> $ladder
     * @param array<string, mixed> $step
     *
     * @return Next
     */
    private function start(array $exercise, array $ladder, array $step, TrainingContext $ctx): array
    {
        if (null !== $ctx->calibration) {
            $placed = CalibrationPlacement::place($exercise['pattern'], $ctx->calibration);
            if (null !== $placed) {
                [$placedExercise, , $placedStep] = $this->locate($placed['exercise']);
                $prescription = $this->prescription($placedExercise, $placedStep, $placedStep['targetSets'], $placedStep['targetMin'], $placed['loadKg'], false, $ctx);

                return ['prescription' => $prescription, 'reason' => 'placement', 'basedOn' => null === $ctx->calibrationId ? [] : [$ctx->calibrationId]];
            }
        }

        $load = $exercise['loadable'] && $step['doubleProgression'] ? self::START_LOAD_KG : null;

        return [
            'prescription' => $this->prescription($exercise, $step, $step['targetSets'], $step['targetMin'], $load, false, $ctx),
            'reason' => 'start',
            'basedOn' => [],
        ];
    }

    /**
     * @param array<string, mixed>  $exercise
     * @param array<string, mixed>  $ladder
     * @param array<string, mixed>  $step
     * @param list<ExerciseSession> $base
     * @param Prescription          $current
     *
     * @return Next
     */
    private function normal(array $exercise, array $ladder, array $step, array $base, ExerciseSession $s0, array $current, TrainingContext $ctx): array
    {
        $s1 = $base[1] ?? null;
        $ids = [$s0->workoutId];
        $tooHard = ExerciseFeeling::TooHard === $s0->feeling;

        if ($tooHard && ($s0->lowest() < $step['targetMin'] || ExerciseFeeling::TooHard === $s1?->feeling)) {
            $basedOn = null === $s1 ? $ids : [$s0->workoutId, $s1->workoutId];
            if (null !== $s0->loadKg && $s0->loadKg > 0) {
                $load = $s0->loadKg - self::LOAD_STEP_KG;
                $p = $this->prescription($exercise, $step, $s0->sets, $step['targetMin'], $load > 0 ? $load : null, $s0->tempo, $ctx);

                return ['prescription' => $p, 'reason' => 'remove_load', 'basedOn' => $basedOn];
            }
            $previous = $ladder['steps'][$step['position'] - 2] ?? null;
            if (null !== $previous) {
                $p = $this->prescription($this->exerciseOf($previous['exercise']), $previous, $s0->sets, $previous['targetMin'], null, false, $ctx);

                return ['prescription' => $p, 'reason' => 'step_down', 'basedOn' => $basedOn];
            }

            return ['prescription' => $current, 'reason' => 'keep', 'basedOn' => $basedOn];
        }

        $last = $ctx->difficulties[0] ?? null;
        if (null !== $last && $last['difficulty'] >= self::HARD_DIFFICULTY) {
            $current['sets'] = max(1, $s0->sets - 1);

            return ['prescription' => $current, 'reason' => 'reduce_volume', 'basedOn' => [$s0->workoutId, $last['id']]];
        }

        $window = $this->stagnationWindow($base, $ctx->now);
        if (null !== $window) {
            $softer = $step['softer'];
            if (null !== $softer) {
                $p = $this->prescription($this->exerciseOf($softer), $step, $current['sets'], $step['targetMin'], $s0->loadKg, false, $ctx);

                return ['prescription' => $p, 'reason' => 'stagnation_variant', 'basedOn' => $window];
            }
            if (!$s0->tempo) {
                $current['tempo'] = true;

                return ['prescription' => $current, 'reason' => 'stagnation_variant', 'basedOn' => $window];
            }
        }

        if ($tooHard) {
            return ['prescription' => $current, 'reason' => 'keep', 'basedOn' => $ids];
        }

        $top = 'seconds' === $exercise['unit'] ? $step['advanceAt'] : $step['targetMax'];
        $hitTop = [] !== $s0->counts && $s0->lowest() >= $top;
        $easyDifficulties = isset($ctx->difficulties[1])
            && $ctx->difficulties[0]['difficulty'] <= self::EASY_DIFFICULTY
            && $ctx->difficulties[1]['difficulty'] <= self::EASY_DIFFICULTY
            && \in_array($s0->feeling, [ExerciseFeeling::Right, ExerciseFeeling::TooEasy], true);
        $easyTwice = ExerciseFeeling::TooEasy === $s0->feeling && ExerciseFeeling::TooEasy === $s1?->feeling;

        if ($easyDifficulties) {
            $ids = [$s0->workoutId, $ctx->difficulties[0]['id'], $ctx->difficulties[1]['id']];
        } elseif ($easyTwice) {
            $ids = [$s0->workoutId, $s1->workoutId];
        }

        if ($hitTop || $easyDifficulties || $easyTwice) {
            return $this->climb($exercise, $ladder, $step, $s0, $current, $ids, $ctx);
        }

        $increment = 'seconds' === $exercise['unit'] ? 5 : 1;
        $current['target'] = min($top, $s0->lowest() + $increment);

        return ['prescription' => $current, 'reason' => 'add_reps', 'basedOn' => $ids];
    }

    /**
     * @param array<string, mixed> $exercise
     * @param array<string, mixed> $ladder
     * @param array<string, mixed> $step
     * @param Prescription         $current
     * @param list<string>         $ids
     *
     * @return Next
     */
    private function climb(array $exercise, array $ladder, array $step, ExerciseSession $s0, array $current, array $ids, TrainingContext $ctx): array
    {
        $next = $ladder['steps'][$step['position']] ?? null;
        if (!$step['doubleProgression'] && null !== $next) {
            $nextExercise = $this->exerciseOf($next['exercise']);
            $load = $nextExercise['loadable'] && $next['doubleProgression'] ? self::START_LOAD_KG : null;
            $p = $this->prescription($nextExercise, $next, $current['sets'], $next['targetMin'], $load, false, $ctx);

            return ['prescription' => $p, 'reason' => 'step_up', 'basedOn' => $ids];
        }
        if ($exercise['loadable']) {
            $current['loadKg'] = ($s0->loadKg ?? 0.0) + self::LOAD_STEP_KG;
            $current['target'] = $step['targetMin'];

            return ['prescription' => $current, 'reason' => 'add_load', 'basedOn' => $ids];
        }
        if (!$s0->tempo) {
            $current['tempo'] = true;
            $current['target'] = $step['targetMin'];

            return ['prescription' => $current, 'reason' => 'add_tempo', 'basedOn' => $ids];
        }

        return ['prescription' => $current, 'reason' => 'keep', 'basedOn' => $ids];
    }

    /**
     * The ids of the sessions since the last gain when the exercise stagnates.
     *
     * @param list<ExerciseSession> $history newest first
     *
     * @return list<string>|null
     */
    private function stagnationWindow(array $history, \DateTimeImmutable $now): ?array
    {
        $normal = array_reverse(array_values(array_filter(
            $history,
            static fn (ExerciseSession $s): bool => null === $s->variant || WorkoutVariant::Normal === $s->variant,
        )));
        if (\count($normal) < 2) {
            return null;
        }

        $gainIndex = 0;
        for ($i = 1, $n = \count($normal); $i < $n; ++$i) {
            $a = $normal[$i - 1];
            $b = $normal[$i];
            if ($b->step > $a->step
                || ($b->loadKg ?? 0.0) > ($a->loadKg ?? 0.0)
                || ($b->tempo && !$a->tempo)
                || ($b->step === $a->step && $b->total() > $a->total())) {
                $gainIndex = $i;
            }
        }
        if ($gainIndex === \count($normal) - 1 || self::days($normal[$gainIndex]->at, $now) <= self::STAGNATION_DAYS) {
            return null;
        }

        return array_map(
            static fn (ExerciseSession $s): string => $s->workoutId,
            \array_slice($normal, $gainIndex),
        );
    }

    /**
     * @param array<string, mixed> $exercise
     * @param array<string, mixed> $step
     *
     * @return Prescription
     */
    private function prescription(array $exercise, array $step, int $sets, int $target, ?float $loadKg, bool $tempo, TrainingContext $ctx): array
    {
        $week = $ctx->programWeek;
        $core = str_starts_with($exercise['pattern'], 'core');
        [$cap, $rir] = match (true) {
            null === $week || $week <= 2 => [2, 3],
            $week <= 4 => [3, 2],
            default => [PHP_INT_MAX, $core ? 2 : 1],
        };

        return [
            'exercise' => $exercise['slug'],
            'name' => $exercise['name'],
            'unit' => $exercise['unit'],
            'step' => $step['position'],
            'sets' => min($sets, $cap),
            'target' => $target,
            'targetMin' => $step['targetMin'],
            'targetMax' => $step['targetMax'],
            'loadKg' => $exercise['loadable'] ? $loadKg : null,
            'restSeconds' => match (true) {
                $core => 60,
                $exercise['eccentric'] || 'high' === $exercise['nervousCost'] => 120,
                default => 90,
            },
            'tempo' => $tempo,
            'targetRir' => $rir,
            'cue' => $exercise['cue'],
        ];
    }

    /**
     * @return array{0: array<string, mixed>, 1: array<string, mixed>, 2: array<string, mixed>}
     */
    private function locate(string $slug): array
    {
        $exercise = $this->catalog->exercise($slug);
        if (null === $exercise) {
            throw new \InvalidArgumentException(\sprintf('Unknown exercise "%s".', $slug));
        }
        $position = $exercise['ladders'][0] ?? null;
        $ladder = null === $position ? null : $this->catalog->ladder($position['ladder']);
        if (null === $ladder) {
            throw new \InvalidArgumentException(\sprintf('Exercise "%s" is in no progression ladder.', $slug));
        }

        return [$exercise, $ladder, $ladder['steps'][$position['position'] - 1]];
    }

    /** @return array<string, mixed> */
    private function exerciseOf(string $slug): array
    {
        return $this->catalog->exercise($slug) ?? throw new \InvalidArgumentException(\sprintf('Unknown exercise "%s".', $slug));
    }

    private static function days(\DateTimeImmutable $from, \DateTimeImmutable $to): int
    {
        $zone = new \DateTimeZone(self::TIME_ZONE);
        $a = $from->setTimezone($zone)->setTime(0, 0);
        $b = $to->setTimezone($zone)->setTime(0, 0);

        return (int) $a->diff($b)->format('%r%a');
    }
}
