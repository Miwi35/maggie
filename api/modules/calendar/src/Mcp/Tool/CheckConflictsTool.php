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

        $tz = new \DateTimeZone('Europe/Paris');
        $start = new \DateTimeImmutable("{$date} {$time}", $tz);
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
