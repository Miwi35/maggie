<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Enum\ExerciseUnit;
use Maggie\Health\Enum\MovementPattern;
use Maggie\Health\Service\ExerciseCatalog;
use PHPUnit\Framework\TestCase;

/** The reference YAML is coherent (MAG-774). */
class ExerciseCatalogTest extends TestCase
{
    private ExerciseCatalog $catalog;

    protected function setUp(): void
    {
        $this->catalog = new ExerciseCatalog(__DIR__.'/../../config/reference');
    }

    public function testEveryPatternHasAMainLadder(): void
    {
        $mains = [];
        foreach ($this->catalog->ladders() as $ladder) {
            if ('main' === $ladder['branch']) {
                $mains[] = $ladder['pattern'];
            }
        }

        foreach (MovementPattern::cases() as $pattern) {
            self::assertContains($pattern->value, $mains, $pattern->value);
        }
    }

    public function testTheVerticalPullLadderFollowsTheMethodOrder(): void
    {
        $ladder = $this->catalog->ladder('vertical-pull');

        self::assertNotNull($ladder);
        self::assertSame(
            ['active-hang', 'scapular-pull-up', 'arch-hang', 'pull-up-negative', 'assisted-pull-up', 'pull-up', 'weighted-pull-up'],
            array_column($ladder['steps'], 'exercise'),
        );
        self::assertSame([1, 2, 3, 4, 5, 6, 7], array_column($ladder['steps'], 'position'));
    }

    public function testEveryLadderReferenceExistsAndIsCoherent(): void
    {
        foreach ($this->catalog->ladders() as $ladder) {
            self::assertNotSame([], $ladder['steps'], $ladder['slug']);
            foreach ($ladder['steps'] as $step) {
                $exercise = $this->catalog->exercise($step['exercise']);
                self::assertNotNull($exercise, $ladder['slug'].' › '.$step['exercise']);
                self::assertSame($ladder['pattern'], $exercise['pattern'], $step['exercise']);
                self::assertLessThanOrEqual($step['targetMax'], $step['advanceAt']);
                self::assertLessThanOrEqual($step['targetMax'], $step['targetMin']);
                if (null !== $step['softer']) {
                    self::assertNotNull($this->catalog->exercise($step['softer']), $step['softer']);
                }
            }
        }
    }

    public function testEveryCueFitsAndSlugsAreUnique(): void
    {
        $exercises = $this->catalog->exercises();
        $slugs = array_column($exercises, 'slug');

        self::assertSame(array_unique($slugs), $slugs);
        foreach ($exercises as $exercise) {
            self::assertLessThanOrEqual(60, mb_strlen($exercise['cue']), $exercise['slug']);
        }
    }

    public function testHoldsAreInSecondsAndTheSuitcaseCarryInMeters(): void
    {
        foreach (['plank', 'active-hang', 'arch-hang', 'hollow-hold'] as $slug) {
            self::assertSame(ExerciseUnit::Seconds->value, $this->catalog->exercise($slug)['unit'] ?? null, $slug);
        }
        self::assertSame(ExerciseUnit::Meters->value, $this->catalog->exercise('suitcase-carry')['unit'] ?? null);
    }

    public function testFiltersByPatternAndEquipment(): void
    {
        foreach ($this->catalog->exercises('squat') as $exercise) {
            self::assertSame('squat', $exercise['pattern']);
        }

        $slugs = array_column($this->catalog->exercises(equipment: ['weighted_bag']), 'slug');
        self::assertContains('goblet-squat', $slugs);
        self::assertContains('air-squat', $slugs);
        self::assertNotContains('pull-up', $slugs);
        self::assertNotContains('weighted-pull-up', $slugs);

        self::assertNotContains('air-squat', array_column($this->catalog->exercises(ladder: 'vertical-pull'), 'slug'));
    }

    public function testAnExerciseKnowsItsLadderPosition(): void
    {
        self::assertSame(
            [['ladder' => 'vertical-pull', 'branch' => 'main', 'position' => 4]],
            $this->catalog->exercise('pull-up-negative')['ladders'] ?? null,
        );
    }
}
