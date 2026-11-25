<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\DayContext;
use Maggie\Calendar\Service\TripDay;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_day_context', description: 'Reads the periods covering a day, so Maggie adapts to that context; events are get_events, tasks get_tasks. Read-only: periods are written with manage_periods. Describes a day as {date, weekend, periods: [{id, name, agenda: {id, name}, allDay, start, end}]}. date is YYYY-MM-DD, today by default, read in Paris. periods are all those covering the day (custody, holidays, a trip…); start and end are the occurrence bounds, instants or days with the end day included.')]
final class GetDayContextTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly DayContext $dayContext,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            [$day] = TripDay::bounds($date ?? (new \DateTimeImmutable('now', new \DateTimeZone(TripDay::TIME_ZONE)))->format('Y-m-d'));
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (\InvalidArgumentException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => 'date']);
        }

        return self::json($this->dayContext->of($user, $day)->toArray());
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
