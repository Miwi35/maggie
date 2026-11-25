<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_proactions', description: 'A proaction is a run of Maggie herself at a set time (a reminder she gives, a check she makes); the owner\'s to-dos are tasks (get_tasks), his appointments events (get_events). Lists the proactions scheduled for the user, by time, in three keys: proactions (one-off runs and the occurrences of a series that have their own run), series (the repeating ones, with their rrule) and, only when from and to are both given, occurrences (the coming runs of the series in the window with no run of their own yet: seriesId, occurrence, scheduledAt, prompt, summary, kind). Each proaction has its prompt, scheduledAt and status (pending, running, completed with its response, failed with its error). from and to (ISO 8601, or YYYY-MM-DD for a whole day, read in Europe/Paris) bound scheduledAt; status filters proactions only.')]
class ListProactionsTool
{
    public function __construct(
        private readonly ProactionRepository $repository,
        private readonly Proactions $proactions,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?string $from = null, ?string $to = null, ?string $status = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $start = self::bound($from, 'from', false);
            $end = self::bound($to, 'to', true);
            $proactions = $this->repository->findForUser($user, $start, $end, ProactionStatus::fromFilter($status));
            $series = $this->repository->findSeriesForUser($user);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $listing = [
            'proactions' => array_map(static fn (Proaction $p) => Proactions::describe($p), $proactions),
            'series' => array_map(static fn (Proaction $p) => Proactions::describe($p), $series),
            'count' => count($proactions),
        ];
        if (null !== $start && null !== $end) {
            $occurrences = [];
            foreach ($series as $one) {
                array_push($occurrences, ...$this->proactions->occurrencesOf($one, $start, $end));
            }
            usort($occurrences, static fn (array $a, array $b): int => strcmp($a['scheduledAt'], $b['scheduledAt']));
            $listing['occurrences'] = $occurrences;
        }

        return json_encode($listing, JSON_THROW_ON_ERROR);
    }

    /** A bare day as `to` reaches the end of that day: « to 2037-01-15 » includes the 15th. */
    private static function bound(?string $value, string $name, bool $isEnd): ?\DateTimeImmutable
    {
        if (null === $value || '' === trim($value)) {
            return null;
        }

        try {
            $read = new \DateTimeImmutable(trim($value), new \DateTimeZone(Proaction::FALLBACK_TIME_ZONE));
        } catch (\Exception $e) {
            throw new \DomainException("{$name} is not a date: \"{$value}\". Use ISO 8601 or YYYY-MM-DD.", 0, $e);
        }

        // `2037-02-30` would roll over to March: what was named has to be what was read.
        if (1 === preg_match('/^\d{4}-\d{2}-\d{2}/', trim($value)) && $read->format('Y-m-d') !== substr(trim($value), 0, 10)) {
            throw new \DomainException("{$name} is not a date: \"{$value}\".");
        }

        return $isEnd && 1 === preg_match('/^\d{4}-\d{2}-\d{2}$/', trim($value)) ? $read->modify('+1 day -1 second') : $read;
    }
}
