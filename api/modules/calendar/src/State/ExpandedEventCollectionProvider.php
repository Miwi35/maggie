<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * The events of a period with their recurring series unfolded, behind `expand=true` (MAG-589).
 *
 * The collection answers the series once, as one row with its `rrule`. A summary of the
 * day (the Accueil's Agenda card) wants the occurrences of the day, and every front would
 * have to run the RRULE rules again to get them; here they run once, in
 * {@see RecurrenceService}, the same ones Maggie reads through.
 *
 * Both reads go through the collection provider, so Elasticsearch serves them and the
 * Doctrine fallback agrees: the events meeting `[endAt[after], startAt[before])`, and the
 * series that began before it. An occurrence is a row of the series with its own
 * `startAt`/`endAt` (or days) and the series' `id`; a modified occurrence is its exception
 * instance, a cancelled one is gone, and so is a cancelled event. The answer is the whole
 * period, never paginated, by start.
 *
 * @implements ProviderInterface<object>
 */
final class ExpandedEventCollectionProvider implements ProviderInterface
{
    public function __construct(
        private readonly ElasticsearchCollectionProvider $inner,
        private readonly RecurrenceService $recurrence,
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

        $period = $this->read($operation, $uriVariables, $context, $filters);
        $others = array_diff_key($filters, array_flip(['startAt', 'endAt', 'exists', 'order']));
        $series = $this->read($operation, $uriVariables, $context, ['exists' => ['rrule' => 'true'], 'startAt' => ['strictly_before' => $after]] + $others);

        $seen = [];
        $events = [];
        foreach ([...$period, ...$series] as $event) {
            $id = (string) $event->getId();
            if (isset($seen[$id]) || EventStatus::Cancelled === $event->getStatus()) {
                continue;
            }
            $seen[$id] = true;
            $events[] = $event;
        }

        return $this->recurrence->expandAll($events, $from, $until);
    }

    /**
     * @param array<string, mixed> $uriVariables
     * @param array<string, mixed> $context
     * @param array<string, mixed> $filters
     *
     * @return list<Event>
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
            throw new BadRequestHttpException(sprintf('"expand" needs the period: "%s[%s]" is missing.', $property, $operator));
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
