<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\ConflictDetectionService;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'check_conflicts', description: 'Check for scheduling conflicts at a given date, time, and duration. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes (default 60).')]
class CheckConflictsTool
{
    public function __construct(
        private readonly ConflictDetectionService $conflictDetectionService,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date, string $time, int $duration = 60): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        // Checked against the shape the description promises, rather than left to
        // the parser: `new DateTimeImmutable('')` means *now*, and " 10:30" means
        // today — so a missing date would quietly answer about a day nobody
        // asked about. A bad argument has to read as a bad argument.
        if (1 !== preg_match('/^\d{4}-\d{2}-\d{2}$/', $date) || 1 !== preg_match('/^\d{1,2}:\d{2}$/', $time)) {
            return json_encode([
                'error' => sprintf('Expected a date as YYYY-MM-DD and a time as HH:MM, got "%s" and "%s".', $date, $time),
            ], JSON_THROW_ON_ERROR);
        }

        if ($duration <= 0) {
            return json_encode(['error' => sprintf('A duration must be a positive number of minutes, got %d.', $duration)], JSON_THROW_ON_ERROR);
        }

        // Europe/Paris, not UTC: the owner asks about 10:30 in their own day, and
        // the events this is compared against are instants. An offsetless slot
        // lands an hour or two away and reports a busy morning as free — MAG-114.
        try {
            $start = new \DateTimeImmutable("{$date} {$time}", new \DateTimeZone('Europe/Paris'));
        } catch (\Exception) {
            // The shape can be right and the value out of range — a month of 99.
            // This used to throw out of the tool, and the agent then saw a
            // transport failure rather than a sentence it could act on.
            return json_encode(['error' => sprintf('"%s %s" is not a real date and time.', $date, $time)], JSON_THROW_ON_ERROR);
        }

        $end = $start->modify("+{$duration} minutes");

        $conflicts = $this->conflictDetectionService->findConflicts($user, $start, $end);

        $result = array_map(fn ($event) => [
            'id' => (string) $event->getId(),
            'summary' => $event->getSummary(),
            'startAt' => $event->getStartAt()->format('c'),
            'endAt' => $event->getEndAt()->format('c'),
            'agenda' => $event->getAgenda()->getName(),
        ], $conflicts);

        return json_encode([
            'hasConflicts' => count($result) > 0,
            'conflicts' => $result,
            'checkedSlot' => [
                'start' => $start->format('c'),
                'end' => $end->format('c'),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
