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
