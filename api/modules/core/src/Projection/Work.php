<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

/** The changes of one message, deduplicated by class and id. */
final class Work
{
    /** @var array<string, Change> */
    private array $changes = [];

    public function record(Change $change): void
    {
        $key = $change->key();

        if (isset($this->changes[$key])) {
            $this->changes[$key]->absorb($change);

            return;
        }

        $this->changes[$key] = $change;
    }

    public function merge(self $other): void
    {
        foreach ($other->changes as $change) {
            $this->record($change);
        }
    }

    /** @return list<Change> */
    public function changes(): array
    {
        return array_values($this->changes);
    }
}
