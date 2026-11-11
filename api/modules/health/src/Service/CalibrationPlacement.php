<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

/**
 * Where a calibration session places the user on each ladder (table 7.1 of the
 * coach method, MAG-776). Each pattern lists its tests from the hardest
 * placement to the easiest: the first test passed decides, the last line is
 * the default. Thresholds are in the tested exercise's unit.
 */
final class CalibrationPlacement
{
    /** @var array<string, list<array{0: ?string, 1: int, 2: string, 3?: float}>> test exercise, minimum reached, placed exercise, load */
    private const TABLE = [
        'vertical_pull' => [
            ['pull-up-negative', 1, 'pull-up-negative'],
            ['scapular-pull-up', 10, 'arch-hang'],
            ['active-hang', 30, 'scapular-pull-up'],
            [null, 0, 'active-hang'],
        ],
        'horizontal_pull' => [
            ['table-row', 11, 'feet-elevated-table-row'],
            ['table-row', 5, 'table-row'],
            [null, 0, 'bent-knee-table-row'],
        ],
        'horizontal_push' => [
            ['push-up', 8, 'push-up'],
            ['incline-push-up', 5, 'incline-push-up'],
            [null, 0, 'wall-push-up'],
        ],
        'vertical_push' => [
            ['incline-pike-push-up', 12, 'pike-push-up'],
            [null, 0, 'incline-pike-push-up'],
        ],
        'squat' => [
            ['air-squat', 15, 'goblet-squat', 10.0],
            ['air-squat', 5, 'air-squat'],
            [null, 0, 'box-squat'],
        ],
        'hinge' => [
            ['single-leg-glute-bridge', 12, 'bag-romanian-deadlift', 10.0],
            ['glute-bridge', 15, 'single-leg-glute-bridge'],
            [null, 0, 'glute-bridge'],
        ],
        'lunge' => [
            ['split-squat', 10, 'reverse-lunge'],
            ['supported-split-squat', 10, 'split-squat'],
            [null, 0, 'supported-split-squat'],
        ],
        'core_anterior' => [
            ['plank', 45, 'hollow-hold'],
            ['plank', 30, 'plank'],
            [null, 0, 'dead-bug'],
        ],
        'core_lateral' => [
            ['side-plank', 45, 'side-plank-hip-dip'],
            ['side-plank', 20, 'side-plank'],
            [null, 0, 'side-plank-knees'],
        ],
    ];

    /**
     * @param array<string, int> $results the best count done per exercise slug
     *
     * @return array{exercise: string, loadKg: ?float}|null null when no test of the pattern was done
     */
    public static function place(string $pattern, array $results): ?array
    {
        $tests = self::TABLE[$pattern] ?? [];
        $done = false;
        foreach ($tests as $test) {
            if (null !== $test[0] && isset($results[$test[0]])) {
                $done = true;
            }
        }
        if (!$done) {
            return null;
        }

        foreach ($tests as $test) {
            if (null === $test[0] || (isset($results[$test[0]]) && $results[$test[0]] >= $test[1])) {
                return ['exercise' => $test[2], 'loadKg' => $test[3] ?? null];
            }
        }

        return null;
    }
}
