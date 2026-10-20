<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

/**
 * A routine as Maggie reads it (MAG-513): what the app shows — the moment,
 * the rhythm, the ticks of the month and since the creation — and nothing that
 * reads as a streak. The seven-day dots of the screen are left out: a count of
 * missed days is the first step to a reproach.
 */
final class RoutineJson
{
    /** @var list<string> */
    private const LEFT_OUT = ['recentDays'];

    /**
     * @param array<string, mixed> $described {@see \Maggie\Calendar\Service\RoutineSchedule::describe()}
     *
     * @return array<string, mixed>
     */
    public static function of(array $described): array
    {
        return array_diff_key($described, array_flip(self::LEFT_OUT));
    }

    /**
     * @param array{date: string, moments: list<array{anchor: string, time: string, routines: list<array<string, mixed>>}>} $day {@see \Maggie\Calendar\Service\RoutineSchedule::day()}
     *
     * @return array<string, mixed>
     */
    public static function day(array $day): array
    {
        $day['moments'] = array_map(
            static fn (array $moment): array => ['routines' => array_map(self::of(...), $moment['routines'])] + $moment,
            $day['moments'],
        );

        return $day;
    }
}
