<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

/**
 * The weights of the order score (MAG-406), as the owner validated them on
 * 7 Oct. — a setting, not code: {@see \Maggie\Calendar\Entity\TaskSettings}.
 * Three groups — timePressure, criticality, priority — every weight a whole
 * number of 0 or more.
 *
 * @phpstan-type Weights array<string, array<string, int>>
 */
final class TaskOrderWeights
{
    public const DEFAULTS = [
        'timePressure' => [
            'overdue' => 65,
            'overduePerDay' => 2,
            'overdueMax' => 85,
            'today' => 55,
            'tomorrow' => 45,
            'in2To3Days' => 30,
            'in4To7Days' => 15,
            'in8To30Days' => 5,
            'later' => 0,
        ],
        'criticality' => ['critical' => 30, 'high' => 20, 'medium' => 10, 'low' => 0],
        'priority' => ['high' => 6, 'medium' => 3, 'low' => 0],
    ];

    /**
     * The five levels of « Ce qui passe en premier » (MAG-407), from « très
     * peu » to « énormément »: each multiplies the spec's weights of its group.
     */
    public const LEVEL_FACTORS = [0.25, 0.5, 1.0, 1.6, 2.4];

    /** The level of the spec's own weights: ×1. */
    public const DEFAULT_LEVEL = 2;

    /**
     * The spec's weights of $group at $level, rounded to whole numbers.
     *
     * @return array<string, int>
     */
    public static function scaled(string $group, int $level): array
    {
        return array_map(
            static fn (int $weight): int => (int) round($weight * self::LEVEL_FACTORS[$level]),
            self::DEFAULTS[$group],
        );
    }

    /**
     * The level each group reads at: the one whose scaled weights are closest
     * to $weights — exact when they were set by level, the nearest when the
     * matrix was changed weight by weight (by Maggie, or through the API).
     *
     * @param array<string, mixed> $weights
     *
     * @return array<string, int>
     */
    public static function levels(array $weights): array
    {
        $weights = self::complete($weights);
        $levels = [];
        foreach (array_keys(self::DEFAULTS) as $group) {
            $best = self::DEFAULT_LEVEL;
            $bestDistance = \PHP_INT_MAX;
            foreach (array_keys(self::LEVEL_FACTORS) as $level) {
                $distance = 0;
                foreach (self::scaled($group, $level) as $key => $weight) {
                    $distance += abs($weights[$group][$key] - $weight);
                }
                if ($distance < $bestDistance) {
                    [$best, $bestDistance] = [$level, $distance];
                }
            }
            $levels[$group] = $best;
        }

        return $levels;
    }

    /**
     * The weights of the groups $given sets a level for, scaled from the
     * spec's — an `orderWeights` patch for {@see merge()}.
     *
     * @return array<string, array<string, int>>
     *
     * @throws \InvalidArgumentException on an unknown group or a level that is not an integer from 0 to 4
     */
    public static function fromLevels(mixed $given): array
    {
        if (!\is_array($given) || [] === $given || array_is_list($given)) {
            throw new \InvalidArgumentException('orderLevels must be an object of groups: '.implode(', ', array_keys(self::DEFAULTS)).'.');
        }

        $weights = [];
        foreach ($given as $group => $level) {
            if (!\array_key_exists($group, self::DEFAULTS)) {
                throw new \InvalidArgumentException(\sprintf('Unknown orderLevels group "%s". Known groups: %s.', $group, implode(', ', array_keys(self::DEFAULTS))));
            }
            if (!\is_int($level) || !\array_key_exists($level, self::LEVEL_FACTORS)) {
                throw new \InvalidArgumentException(\sprintf('orderLevels.%s must be an integer from 0 to %d.', $group, \count(self::LEVEL_FACTORS) - 1));
            }
            $weights[$group] = self::scaled($group, $level);
        }

        return $weights;
    }

    /**
     * The weights stored, completed with the defaults for any key they lack —
     * a weight added later reads its default, not zero.
     *
     * @param array<string, mixed> $stored
     *
     * @return Weights
     */
    public static function complete(array $stored): array
    {
        $weights = self::DEFAULTS;
        foreach (self::DEFAULTS as $group => $keys) {
            foreach ($keys as $key => $default) {
                $value = $stored[$group][$key] ?? null;
                if (\is_int($value) && $value >= 0) {
                    $weights[$group][$key] = $value;
                }
            }
        }

        return $weights;
    }

    /**
     * $current with the weights $given changes; the others keep their value.
     *
     * @param array<string, mixed> $current
     *
     * @return Weights
     *
     * @throws \InvalidArgumentException on an unknown group or key, or a weight that is not an integer of 0 or more
     */
    public static function merge(array $current, mixed $given): array
    {
        if (!\is_array($given) || ([] !== $given && array_is_list($given))) {
            throw new \InvalidArgumentException('orderWeights must be an object of groups: '.implode(', ', array_keys(self::DEFAULTS)).'.');
        }

        $weights = self::complete($current);
        foreach ($given as $group => $keys) {
            if (!\array_key_exists($group, self::DEFAULTS)) {
                throw new \InvalidArgumentException(\sprintf('Unknown orderWeights group "%s". Known groups: %s.', $group, implode(', ', array_keys(self::DEFAULTS))));
            }
            if (!\is_array($keys) || ([] !== $keys && array_is_list($keys))) {
                throw new \InvalidArgumentException(\sprintf('orderWeights.%s must be an object of weights.', $group));
            }
            foreach ($keys as $key => $value) {
                if (!\array_key_exists($key, self::DEFAULTS[$group])) {
                    throw new \InvalidArgumentException(\sprintf('Unknown weight "orderWeights.%s.%s". Known weights: %s.', $group, $key, implode(', ', array_keys(self::DEFAULTS[$group]))));
                }
                if (!\is_int($value) || $value < 0) {
                    throw new \InvalidArgumentException(\sprintf('orderWeights.%s.%s must be an integer of 0 or more.', $group, $key));
                }
                $weights[$group][$key] = $value;
            }
        }

        return $weights;
    }
}
