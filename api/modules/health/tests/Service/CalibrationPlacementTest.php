<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Service\CalibrationPlacement;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** Table 7.1 of the coach method: where a calibration session places the user (MAG-776). */
class CalibrationPlacementTest extends TestCase
{
    /** @return iterable<string, array{string, array<string, int>, string, float|null}> */
    public static function placements(): iterable
    {
        yield 'vertical pull, a negative' => ['vertical_pull', ['pull-up-negative' => 1], 'pull-up-negative', null];
        yield 'vertical pull, scapular pull-ups' => ['vertical_pull', ['scapular-pull-up' => 10, 'active-hang' => 40], 'arch-hang', null];
        yield 'vertical pull, a hang' => ['vertical_pull', ['active-hang' => 30], 'scapular-pull-up', null];
        yield 'vertical pull, a short hang' => ['vertical_pull', ['active-hang' => 12], 'active-hang', null];
        yield 'horizontal pull, high' => ['horizontal_pull', ['table-row' => 11], 'feet-elevated-table-row', null];
        yield 'horizontal pull, middle' => ['horizontal_pull', ['table-row' => 6], 'table-row', null];
        yield 'horizontal pull, low' => ['horizontal_pull', ['table-row' => 3], 'bent-knee-table-row', null];
        yield 'horizontal push, push-ups' => ['horizontal_push', ['push-up' => 8], 'push-up', null];
        yield 'horizontal push, incline' => ['horizontal_push', ['push-up' => 3, 'incline-push-up' => 5], 'incline-push-up', null];
        yield 'horizontal push, wall' => ['horizontal_push', ['incline-push-up' => 2], 'wall-push-up', null];
        yield 'vertical push, pike' => ['vertical_push', ['incline-pike-push-up' => 12], 'pike-push-up', null];
        yield 'vertical push, incline pike' => ['vertical_push', ['incline-pike-push-up' => 7], 'incline-pike-push-up', null];
        yield 'squat, loaded' => ['squat', ['air-squat' => 15], 'goblet-squat', 10.0];
        yield 'squat, bodyweight' => ['squat', ['air-squat' => 8], 'air-squat', null];
        yield 'squat, box' => ['squat', ['air-squat' => 2], 'box-squat', null];
        yield 'hinge, loaded' => ['hinge', ['single-leg-glute-bridge' => 12], 'bag-romanian-deadlift', 10.0];
        yield 'hinge, single leg' => ['hinge', ['glute-bridge' => 15], 'single-leg-glute-bridge', null];
        yield 'hinge, bridge' => ['hinge', ['glute-bridge' => 9], 'glute-bridge', null];
        yield 'lunge, reverse' => ['lunge', ['split-squat' => 10], 'reverse-lunge', null];
        yield 'lunge, split' => ['lunge', ['supported-split-squat' => 10], 'split-squat', null];
        yield 'lunge, supported' => ['lunge', ['supported-split-squat' => 4], 'supported-split-squat', null];
        yield 'core anterior, hollow' => ['core_anterior', ['plank' => 45], 'hollow-hold', null];
        yield 'core anterior, plank' => ['core_anterior', ['plank' => 30], 'plank', null];
        yield 'core anterior, dead bug' => ['core_anterior', ['plank' => 10], 'dead-bug', null];
        yield 'core lateral, hip dip' => ['core_lateral', ['side-plank' => 45], 'side-plank-hip-dip', null];
        yield 'core lateral, side plank' => ['core_lateral', ['side-plank' => 20], 'side-plank', null];
        yield 'core lateral, knees' => ['core_lateral', ['side-plank' => 8], 'side-plank-knees', null];
    }

    /** @param array<string, int> $results */
    #[DataProvider('placements')]
    public function testItPlacesTheUser(string $pattern, array $results, string $exercise, ?float $loadKg): void
    {
        self::assertSame(['exercise' => $exercise, 'loadKg' => $loadKg], CalibrationPlacement::place($pattern, $results));
    }

    public function testWithoutATestOfThePatternThereIsNoPlacement(): void
    {
        self::assertNull(CalibrationPlacement::place('squat', ['plank' => 40]));
    }
}
