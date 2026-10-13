<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Mcp;

use Maggie\Core\Elasticsearch\SearchService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Time\DayBound;
use Maggie\Core\Time\Weekday;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search', description: "Full-text search across the user's own data stored in Maggie (events, tasks, recipes, products, grocery lists, notifications). Returns matching results with highlights. Every event result carries the day of the week of its local dates (data.startWeekday, endWeekday for a timed event; startWeekday and lastDayWeekday for a whole-day one, whose endDate is EXCLUDED — lastDay is its last day): say the weekday from those fields or from the date_time tool, never work it out yourself. It does NOT search the Internet: for public facts (official dates, opening hours, addresses, news) use the web_search tool.")]
class SearchTool
{
    public function __construct(
        private readonly SearchService $searchService,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $query, ?string $types = null, int $limit = 10): string
    {
        $user = $this->userContext->getUser();
        if (null === $user) {
            return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
        }

        $indices = null !== $types
            ? array_map('trim', explode(',', $types))
            : null;

        try {
            $results = $this->searchService->search(
                query: $query,
                userId: (string) $user->getId(),
                indices: $indices,
                size: $limit,
            );

            foreach ($results['results'] as &$result) {
                if ('events' === $result['index']) {
                    $result['data'] = $this->withWeekdays($result['data']);
                }
            }
            unset($result);

            return json_encode($results, JSON_THROW_ON_ERROR);
        } catch (\Throwable $e) {
            return json_encode([
                'error' => 'Search failed: '.$e->getMessage(),
                'total' => 0,
                'results' => [],
            ], JSON_THROW_ON_ERROR);
        }
    }

    /**
     * The day of the week of an event's local dates, worked out here so that the model never has to (MAG-431).
     *
     * @param array<string, mixed> $event
     *
     * @return array<string, mixed>
     */
    private function withWeekdays(array $event): array
    {
        try {
            $zone = new \DateTimeZone(\is_string($event['timeZone'] ?? null) ? $event['timeZone'] : DayBound::OWNER_TIME_ZONE);
        } catch (\Exception) {
            $zone = new \DateTimeZone(DayBound::OWNER_TIME_ZONE);
        }

        $local = static fn (mixed $instant): ?\DateTimeImmutable => \is_string($instant) && '' !== $instant
            ? (new \DateTimeImmutable($instant))->setTimezone($zone)
            : null;

        $startAt = $local($event['startAt'] ?? null);
        $endAt = $local($event['endAt'] ?? null);
        if (null !== $startAt && null !== $endAt) {
            $event['startWeekday'] = Weekday::fr($startAt);
            $event['endWeekday'] = Weekday::fr($endAt);
        }

        $startDate = \is_string($event['startDate'] ?? null) ? \DateTimeImmutable::createFromFormat('!Y-m-d', $event['startDate']) : false;
        if (false !== $startDate) {
            $until = \is_string($event['endDate'] ?? null) ? \DateTimeImmutable::createFromFormat('!Y-m-d', $event['endDate']) : false;
            $lastDay = ($until ?: $startDate->modify('+1 day'))->modify('-1 day');
            $event['startWeekday'] = Weekday::fr($startDate);
            $event['lastDay'] = $lastDay->format('Y-m-d');
            $event['lastDayWeekday'] = Weekday::fr($lastDay);
        }

        return $event;
    }
}
