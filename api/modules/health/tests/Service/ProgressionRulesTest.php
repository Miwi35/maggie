<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Dto\ExerciseSession;
use Maggie\Health\Dto\TrainingContext;
use Maggie\Health\Enum\ExerciseFeeling;
use Maggie\Health\Enum\WorkoutVariant;
use Maggie\Health\Service\ExerciseCatalog;
use Maggie\Health\Service\ProgressionRules;
use PHPUnit\Framework\TestCase;

/** The progression rules of the coach method (MAG-776), one case per row of the tables. */
class ProgressionRulesTest extends TestCase
{
    private ProgressionRules $rules;
    private \DateTimeImmutable $now;

    protected function setUp(): void
    {
        $this->rules = new ProgressionRules(new ExerciseCatalog(__DIR__.'/../../config/reference'));
        $this->now = new \DateTimeImmutable('2026-11-10T12:00:00+01:00');
    }

    /** @param list<int> $counts */
    private function session(string $id, int $daysAgo, array $counts, ?ExerciseFeeling $feeling = null, ?float $load = null, bool $tempo = false, ?WorkoutVariant $variant = WorkoutVariant::Normal, int $sets = 3): ExerciseSession
    {
        return new ExerciseSession($id, $this->now->modify("-{$daysAgo} days"), $variant, 1, $sets, $load, $tempo, $feeling, $counts);
    }

    /** @param list<array{id: string, difficulty: int}> $difficulties */
    private function context(array $difficulties = [], ?int $week = 6): TrainingContext
    {
        return new TrainingContext($this->now, $week, $difficulties);
    }

    public function testTheRepsClimbOneByOneBelowTheTop(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w1', 3, [8, 7, 6])], $this->context());

        self::assertSame('add_reps', $result['reason']);
        self::assertSame(7, $result['prescription']['target']);
        self::assertSame('assisted-pull-up', $result['prescription']['exercise']);
        self::assertSame(['w1'], $result['basedOn']);
    }

    public function testEverySetAtTheTopStepsUpTheLadder(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w1', 3, [8, 8, 8])], $this->context());

        self::assertSame('step_up', $result['reason']);
        self::assertSame('pull-up', $result['prescription']['exercise']);
        self::assertSame(4, $result['prescription']['target']);
    }

    public function testALoadedExerciseAddsFiveKilosInsteadOfSteppingUp(): void
    {
        $result = $this->rules->next('goblet-squat', 'normal', [$this->session('w1', 3, [12, 12, 12], load: 10.0)], $this->context());

        self::assertSame('add_load', $result['reason']);
        self::assertSame(15.0, $result['prescription']['loadKg']);
        self::assertSame(8, $result['prescription']['target']);
    }

    public function testTooEasyTwiceInARowStepsUp(): void
    {
        $history = [
            $this->session('w2', 3, [6, 6, 6], ExerciseFeeling::TooEasy),
            $this->session('w1', 10, [6, 6, 6], ExerciseFeeling::TooEasy),
        ];

        $result = $this->rules->next('pull-up', 'normal', $history, $this->context());

        self::assertSame('step_up', $result['reason']);
        self::assertSame('weighted-pull-up', $result['prescription']['exercise']);
        self::assertSame(['w2', 'w1'], $result['basedOn']);
    }

    public function testTooHardBelowTheFloorStepsDown(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w1', 3, [4, 3, 3], ExerciseFeeling::TooHard)], $this->context());

        self::assertSame('step_down', $result['reason']);
        self::assertSame('pull-up-negative', $result['prescription']['exercise']);
    }

    public function testTooHardOnALoadedExerciseRemovesTheLoad(): void
    {
        $result = $this->rules->next('goblet-squat', 'normal', [$this->session('w1', 3, [5, 4, 4], ExerciseFeeling::TooHard, 15.0)], $this->context());

        self::assertSame('remove_load', $result['reason']);
        self::assertSame(10.0, $result['prescription']['loadKg']);
    }

    public function testTooHardWithinTheRangeKeepsTheWork(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w1', 3, [6, 5, 5], ExerciseFeeling::TooHard)], $this->context());

        self::assertSame('keep', $result['reason']);
        self::assertSame(5, $result['prescription']['target']);
    }

    public function testAHoldAddsFiveSeconds(): void
    {
        $result = $this->rules->next('plank', 'normal', [$this->session('w1', 3, [25, 25, 25])], $this->context());

        self::assertSame('add_reps', $result['reason']);
        self::assertSame(30, $result['prescription']['target']);
        self::assertSame('seconds', $result['prescription']['unit']);
        self::assertSame(60, $result['prescription']['restSeconds']);
    }

    public function testADifficultyOfNineDropsASet(): void
    {
        $context = $this->context([['id' => 'w1', 'difficulty' => 9]]);

        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w1', 3, [6, 6, 6])], $context);

        self::assertSame('reduce_volume', $result['reason']);
        self::assertSame(2, $result['prescription']['sets']);
    }

    public function testASessionEasyTwiceLiftsTheExercisesThatFeltRight(): void
    {
        $context = $this->context([['id' => 'w2', 'difficulty' => 4], ['id' => 'w1', 'difficulty' => 5]]);

        $result = $this->rules->next('assisted-pull-up', 'normal', [$this->session('w2', 3, [6, 6, 6], ExerciseFeeling::Right)], $context);

        self::assertSame('step_up', $result['reason']);
    }

    public function testFifteenDaysWithoutGainOffersTheSofterVariant(): void
    {
        $history = [
            $this->session('w2', 8, [6, 6, 6]),
            $this->session('w1', 15, [6, 6, 6]),
        ];

        $result = $this->rules->next('assisted-pull-up', 'normal', $history, $this->context());

        self::assertSame('stagnation_variant', $result['reason']);
        self::assertSame('jumping-pull-up', $result['prescription']['exercise']);
        self::assertSame(['w1', 'w2'], $result['basedOn']);
        self::assertTrue($this->rules->isStagnating($history, $this->now));
    }

    public function testWithoutASofterVariantTheTempoIsAdded(): void
    {
        $result = $this->rules->next('plank', 'normal', [$this->session('w2', 8, [25, 25, 25]), $this->session('w1', 15, [25, 25, 25])], $this->context());

        self::assertSame('stagnation_variant', $result['reason']);
        self::assertTrue($result['prescription']['tempo']);
    }

    public function testAGainResetsTheStagnationClock(): void
    {
        $history = [
            $this->session('w3', 3, [7, 7, 7]),
            $this->session('w2', 10, [6, 6, 6]),
            $this->session('w1', 17, [6, 6, 6]),
        ];

        self::assertFalse($this->rules->isStagnating($history, $this->now));
    }

    public function testNothingDoneYetStartsAtTheBottomOfTheStep(): void
    {
        $result = $this->rules->next('goblet-squat', 'normal', [], $this->context());

        self::assertSame('start', $result['reason']);
        self::assertSame(5.0, $result['prescription']['loadKg']);
        self::assertSame(8, $result['prescription']['target']);
        self::assertSame([], $result['basedOn']);
    }

    public function testTheCalibrationSetsTwoSetsThenThree(): void
    {
        $first = $this->rules->next('table-row', 'normal', [], $this->context(week: 1));
        $third = $this->rules->next('table-row', 'normal', [], $this->context(week: 3));
        $fifth = $this->rules->next('table-row', 'normal', [], $this->context(week: 5));

        self::assertSame([2, 3, 3], [$first['prescription']['sets'], $third['prescription']['sets'], $fifth['prescription']['sets']]);
        self::assertSame([3, 2, 1], [$first['prescription']['targetRir'], $third['prescription']['targetRir'], $fifth['prescription']['targetRir']]);
    }

    public function testACalibrationPlacesTheUserOnTheLadder(): void
    {
        $context = new TrainingContext($this->now, 3, [], ['table-row' => 6], 'cal1');

        $result = $this->rules->next('bent-knee-table-row', 'normal', [], $context);

        self::assertSame('placement', $result['reason']);
        self::assertSame('table-row', $result['prescription']['exercise']);
        self::assertSame(['cal1'], $result['basedOn']);
    }

    public function testADeloadHalvesTheSetsOnTheSameStep(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'deload', [$this->session('w1', 3, [7, 7, 7])], $this->context());

        self::assertSame('deload', $result['reason']);
        self::assertSame(2, $result['prescription']['sets']);
        self::assertSame(3, $result['prescription']['targetRir']);
        self::assertSame('assisted-pull-up', $result['prescription']['exercise']);
    }

    public function testASoftSessionDropsASetAndKeepsAReserve(): void
    {
        $result = $this->rules->next('assisted-pull-up', 'soft', [$this->session('w1', 3, [7, 7, 7])], $this->context());

        self::assertSame('soft', $result['reason']);
        self::assertSame(2, $result['prescription']['sets']);
        self::assertSame(3, $result['prescription']['targetRir']);
    }

    public function testASoftSessionReplacesAnEccentricByThePreviousStep(): void
    {
        $result = $this->rules->next('pull-up-negative', 'soft', [$this->session('w1', 3, [5, 5, 5])], $this->context());

        self::assertSame('arch-hang', $result['prescription']['exercise']);
    }

    public function testADeloadSessionIsNotTheBaseOfTheNextProgression(): void
    {
        $history = [
            $this->session('w2', 3, [6, 6], variant: WorkoutVariant::Deload, sets: 2),
            $this->session('w1', 10, [7, 7, 7]),
        ];

        $result = $this->rules->next('assisted-pull-up', 'normal', $history, $this->context());

        self::assertSame(['w1'], $result['basedOn']);
        self::assertSame(8, $result['prescription']['target']);
    }

    public function testUnknownInputsAreRefused(): void
    {
        foreach ([['nope', 'normal'], ['pull-up', 'hard'], ['jumping-pull-up', 'normal']] as [$slug, $variant]) {
            try {
                $this->rules->next($slug, $variant, [], $this->context());
                self::fail("{$slug}/{$variant} should be refused.");
            } catch (\InvalidArgumentException) {
                $this->addToAssertionCount(1);
            }
        }
    }

    public function testTheProgramWeekAndPhase(): void
    {
        $start = new \DateTimeImmutable('2026-10-05T18:00:00+01:00');

        self::assertSame(1, ProgressionRules::programWeek($start, new \DateTimeImmutable('2026-10-11T10:00:00+01:00')));
        self::assertSame(2, ProgressionRules::programWeek($start, new \DateTimeImmutable('2026-10-12T10:00:00+01:00')));
        self::assertNull(ProgressionRules::programWeek(null, $this->now));
        self::assertSame(['calibration', 'calibration', 'ramp_up', 'ramp_up', 'normal'], array_map(ProgressionRules::phase(...), [null, 2, 3, 4, 5]));
    }

    public function testTheDeloadIsDueAfterEightWeeks(): void
    {
        $start = new \DateTimeImmutable('2026-09-07T10:00:00+02:00');

        self::assertSame(['due' => true, 'reason' => 'scheduled', 'weeksSinceLast' => 9], ProgressionRules::deload($start, null, $this->now, 0, 0, false));
        self::assertFalse(ProgressionRules::deload($start, $this->now->modify('-3 weeks'), $this->now, 0, 0, false)['due']);
        self::assertSame('stagnation', ProgressionRules::deload($start, null, $this->now, 2, 2, false)['reason']);
        self::assertSame('hard_sessions', ProgressionRules::deload($start->modify('+7 weeks'), null, $this->now, 1, 2, false)['reason']);
        self::assertFalse(ProgressionRules::deload($start, null, $this->now, 0, 0, true)['due']);
    }
}
