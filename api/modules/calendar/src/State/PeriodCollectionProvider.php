<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Service\PeriodRecurrence;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * The periods of a range with their series unfolded, behind `expand=true` (MAG-809):
 * the same parameters and the same answer as {@see ExpandedEventCollectionProvider}.
 *
 * Two reads through the collection provider: the periods meeting
 * `[endAt[after], startAt[before])`, and the series begun before it. Each occurrence is a
 * row of its series with its own `startAt`/`endAt` (or days) and the series' `id`. A
 * period has no exception instance and no cancelled status, so nothing is left out but
 * what falls outside. The answer is the whole range, never paginated, by start.
 *
 * @implements ProviderInterface<object>
 */
final class PeriodCollectionProvider implements ProviderInterface
{
    public function __construct(
        private readonly ElasticsearchCollectionProvider $inner,
        private readonly PeriodRecurrence $recurrence,
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $filters = $context['filters'] ?? [];
        if (!\array_key_exists('expand', $filters)) {
            return $this->inner->provide($operation, $uriVariables, $context);
        }

        $expand = filter_var($filters['expand'], FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE);
        if (null === $expand) {
            throw new BadRequestHttpException('"expand" is true or false.');
        }
        unset($filters['expand']);

        if (!$expand) {
            return $this->inner->provide($operation, $uriVariables, ['filters' => $filters] + $context);
        }

        $after = self::bound($filters, 'endAt', 'after');
        $before = self::bound($filters, 'startAt', 'before');
        $from = self::instant($after, 'endAt[after]');
        $until = self::instant($before, 'startAt[before]');

        // The range is half-open: a period ending at its start, or starting at its end, is not in it.
        $strict = ['endAt' => ['strictly_after' => $after] + ($filters['endAt'] ?? []), 'startAt' => ['strictly_before' => $before] + ($filters['startAt'] ?? [])];
        unset($strict['endAt']['after'], $strict['startAt']['before']);
        $range = $this->read($operation, $uriVariables, $context, $strict + $filters);
        $others = array_diff_key($filters, array_flip(['startAt', 'endAt', 'exists', 'order']));
        $series = $this->read($operation, $uriVariables, $context, ['exists' => ['rrule' => 'true'], 'startAt' => ['strictly_before' => $after]] + $others);

        $periods = [];
        foreach ([...$range, ...$series] as $period) {
            $periods[(string) $period->getId()] = $period;
        }

        return $this->recurrence->expandAll(array_values($periods), $from, $until);
    }

    /**
     * @param array<string, mixed> $uriVariables
     * @param array<string, mixed> $context
     * @param array<string, mixed> $filters
     *
     * @return list<Period>
     */
    private function read(Operation $operation, array $uriVariables, array $context, array $filters): array
    {
        $answer = $this->inner->provide($operation, $uriVariables, ['filters' => $filters + ['pagination' => 'false']] + $context);

        return array_values(\is_array($answer) ? $answer : iterator_to_array($answer instanceof \Traversable ? $answer : new \ArrayIterator([]), false));
    }

    /** @param array<string, mixed> $filters */
    private static function bound(array $filters, string $property, string $operator): string
    {
        $value = $filters[$property][$operator] ?? null;
        if (!\is_string($value) || '' === trim($value)) {
            throw new BadRequestHttpException(sprintf('"expand" needs the range: "%s[%s]" is missing.', $property, $operator));
        }

        return $value;
    }

    private static function instant(string $value, string $name): \DateTimeImmutable
    {
        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw new BadRequestHttpException(sprintf('"%s" is not a date.', $name));
        }
    }
}
