<?php

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Exception\InvalidWorkoutException;
use Maggie\Health\Service\ExerciseCatalog;
use Maggie\Health\Service\WorkoutRules;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class WorkoutRulesTest extends TestCase
{
    private WorkoutRules $rules;

    protected function setUp(): void
    {
        $this->rules = new WorkoutRules(new ExerciseCatalog(__DIR__.'/../../config/reference'));
    }

    public function testTheEnumsAreResolved(): void
    {
        self::assertSame(WorkoutType::Climbing, $this->rules->type('climbing'));
        self::assertSame(WorkoutFeeling::VeryHard, $this->rules->feeling('very_hard'));
        self::assertNull($this->rules->feeling(null));
        self::assertSame(WorkoutSource::Planned, $this->rules->source('planned'));
    }

    public function testUnknownEnumValuesAreRefusedNamingTheField(): void
    {
        foreach ([
            'type' => fn () => $this->rules->type('yoga'),
            'feeling' => fn () => $this->rules->feeling('meh'),
            'source' => fn () => $this->rules->source('garmin'),
        ] as $field => $call) {
            try {
                $call();
                self::fail("{$field} should be refused.");
            } catch (InvalidWorkoutException $e) {
                self::assertSame($field, $e->field);
                self::assertStringContainsString($field, $e->getMessage());
            }
        }
    }

    public function testStartedAtKeepsItsOffset(): void
    {
        self::assertSame('2026-10-20T07:10:00+02:00', $this->rules->startedAt('2026-10-20T07:10:00+02:00')->format(\DATE_ATOM));
        self::assertSame('2026-10-20T05:10:00+00:00', $this->rules->startedAt('2026-10-20T05:10:00Z')->format(\DATE_ATOM));
    }

    /** @return iterable<string, array{string}> */
    public static function badStarts(): iterable
    {
        yield 'date only' => ['2026-10-20'];
        yield 'no offset' => ['2026-10-20T07:10:00'];
        yield 'no seconds, no offset' => ['2026-10-20T07:10'];
        yield 'garbage' => ['ce matin'];
        yield 'impossible month' => ['2026-13-40T07:10:00+02:00'];
    }

    #[DataProvider('badStarts')]
    public function testStartedAtWithoutTimeOrOffsetIsRefused(string $value): void
    {
        try {
            $this->rules->startedAt($value);
            self::fail('Should be refused.');
        } catch (InvalidWorkoutException $e) {
            self::assertSame('startedAt', $e->field);
        }
    }

    public function testTheBoundsAreAccepted(): void
    {
        $this->rules->assert('Course', 1, 0, 0, 20);
        $this->rules->assert(str_repeat('a', 255), 1440, 1_000_000, 20_000, 250, str_repeat('n', 2000), str_repeat('e', 255));

        $this->expectNotToPerformAssertions();
    }

    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function outOfBounds(): iterable
    {
        yield 'blank title' => ['title', ['title' => ' ']];
        yield 'long title' => ['title', ['title' => str_repeat('a', 256)]];
        yield 'duration 0' => ['durationMinutes', ['durationMinutes' => 0]];
        yield 'duration 1441' => ['durationMinutes', ['durationMinutes' => 1441]];
        yield 'distance -1' => ['distanceMeters', ['distanceMeters' => -1]];
        yield 'distance too long' => ['distanceMeters', ['distanceMeters' => 1_000_001]];
        yield 'kcal -1' => ['kcal', ['kcal' => -1]];
        yield 'kcal too high' => ['kcal', ['kcal' => 20_001]];
        yield 'heart rate 19' => ['avgHeartRate', ['avgHeartRate' => 19]];
        yield 'heart rate 251' => ['avgHeartRate', ['avgHeartRate' => 251]];
        yield 'notes too long' => ['notes', ['notes' => str_repeat('n', 2001)]];
        yield 'external id too long' => ['externalId', ['externalId' => str_repeat('e', 256)]];
    }

    /** @param array<string, mixed> $override */
    #[DataProvider('outOfBounds')]
    public function testOutOfBoundsFiguresAreRefusedNamingTheField(string $field, array $override): void
    {
        $args = $override + ['title' => 'Course', 'durationMinutes' => 30, 'distanceMeters' => null, 'kcal' => null, 'avgHeartRate' => null, 'notes' => null, 'externalId' => null];

        try {
            $this->rules->assert(...$args);
            self::fail('Should be refused.');
        } catch (InvalidWorkoutException $e) {
            self::assertSame($field, $e->field);
        }
    }

    /** @return array<string, mixed> */
    private static function plank(array $more = []): array
    {
        return $more + ['exercise' => 'plank', 'step' => 2, 'sets' => 3, 'target' => 30, 'targetMin' => 20, 'targetMax' => 45, 'restSeconds' => 60];
    }

    public function testAnExerciseIsTakenWithTheCatalogueNameAndUnit(): void
    {
        $exercise = $this->rules->exercises([self::plank(['done' => [['seconds' => 30], ['seconds' => 25]], 'feeling' => 'too_hard'])], WorkoutSource::Manual)[0];

        self::assertSame('Planche', $exercise->name);
        self::assertSame('seconds', $exercise->unit);
        self::assertFalse($exercise->tempo);
        self::assertNull($exercise->loadKg);
        self::assertSame([30, 25], array_column($exercise->done, 'seconds'));
        self::assertSame('too_hard', $exercise->feeling?->value);
    }

    /** @return iterable<string, array{array<string, mixed>, WorkoutSource, string}> */
    public static function refusedExercises(): iterable
    {
        yield 'an unknown slug' => [self::plank(['exercise' => 'moonwalk']), WorkoutSource::Planned, 'exercises[0].exercise'];
        yield 'a load on a bodyweight exercise' => [self::plank(['loadKg' => 5]), WorkoutSource::Planned, 'exercises[0].loadKg'];
        yield 'a set in repetitions of an exercise in seconds' => [self::plank(['done' => [['reps' => 30]]]), WorkoutSource::Manual, 'exercises[0].done[0].reps'];
        yield 'a set without its count' => [self::plank(['done' => [[]]]), WorkoutSource::Manual, 'exercises[0].done[0].seconds'];
        yield 'a feeling on a planned session' => [self::plank(['feeling' => 'right']), WorkoutSource::Planned, 'exercises[0].feeling'];
        yield 'an unknown feeling' => [self::plank(['feeling' => 'meh']), WorkoutSource::Manual, 'exercises[0].feeling'];
        yield 'sets done on a planned session' => [self::plank(['done' => [['seconds' => 30]]]), WorkoutSource::Planned, 'exercises[0].done'];
        yield 'a missing target' => [array_diff_key(self::plank(), ['target' => true]), WorkoutSource::Planned, 'exercises[0].target'];
        yield 'a range upside down' => [self::plank(['targetMin' => 50]), WorkoutSource::Planned, 'exercises[0].targetMin'];
        yield 'a RIR of 5' => [self::plank(['targetRir' => 5]), WorkoutSource::Planned, 'exercises[0].targetRir'];
        yield 'an unknown field' => [self::plank(['reps' => 3]), WorkoutSource::Planned, 'exercises[0].reps'];
    }

    /** @param array<string, mixed> $exercise */
    #[DataProvider('refusedExercises')]
    public function testARefusedExerciseNamesItsField(array $exercise, WorkoutSource $source, string $field): void
    {
        try {
            $this->rules->exercises([$exercise], $source);
            self::fail("{$field} should be refused.");
        } catch (InvalidWorkoutException $e) {
            self::assertSame($field, $e->field);
        }
    }

    public function testTheDifficultyIsFromOneToTenOnASessionDone(): void
    {
        self::assertSame(7, $this->rules->difficulty(7, WorkoutSource::Manual));
        self::assertNull($this->rules->difficulty(null, WorkoutSource::Planned));
        foreach ([[11, WorkoutSource::Manual], [0, WorkoutSource::Watch], [5, WorkoutSource::Planned]] as [$value, $source]) {
            try {
                $this->rules->difficulty($value, $source);
                self::fail("{$value} should be refused.");
            } catch (InvalidWorkoutException $e) {
                self::assertSame('difficulty', $e->field);
            }
        }
    }
}
