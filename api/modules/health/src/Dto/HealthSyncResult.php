<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

/** What a Health Connect batch did (MAG-708): per list, the items created, updated and left as they were. */
final class HealthSyncResult
{
    /** @var array<'workouts'|'measurements'|'days', array{created: int, updated: int, unchanged: int}> */
    private array $counts = [
        'workouts' => ['created' => 0, 'updated' => 0, 'unchanged' => 0],
        'measurements' => ['created' => 0, 'updated' => 0, 'unchanged' => 0],
        'days' => ['created' => 0, 'updated' => 0, 'unchanged' => 0],
    ];

    /**
     * @param 'workouts'|'measurements'|'days' $list
     * @param 'created'|'updated'|'unchanged'  $outcome
     */
    public function count(string $list, string $outcome): void
    {
        ++$this->counts[$list][$outcome];
    }

    /** @return array<'workouts'|'measurements'|'days', array{created: int, updated: int, unchanged: int}> */
    public function toArray(): array
    {
        return $this->counts;
    }
}
