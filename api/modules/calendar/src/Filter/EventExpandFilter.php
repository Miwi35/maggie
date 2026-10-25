<?php

declare(strict_types=1);

namespace Maggie\Calendar\Filter;

use ApiPlatform\Metadata\FilterInterface;

/**
 * `expand=true`: documents the parameter {@see \Maggie\Calendar\State\ExpandedEventCollectionProvider}
 * reads before any query is built. Nothing to apply here.
 */
final class EventExpandFilter implements FilterInterface
{
    /**
     * @return array<string, array<string, mixed>>
     */
    public function getDescription(string $resourceClass): array
    {
        return [
            'expand' => [
                'property' => 'expand',
                'type' => 'bool',
                'required' => false,
                'description' => 'true unfolds the recurring series into their occurrences within the period; endAt[after] and startAt[before] are then required, and the answer is the whole period, never paginated.',
            ],
        ];
    }
}
