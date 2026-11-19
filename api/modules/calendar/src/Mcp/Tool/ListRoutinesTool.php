<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\RoutineJson;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_routines', description: 'Reads routines. Without date: all, as {routines}. With date (YYYY-MM-DD): active routines due that day, as {date, moments: [{anchor, time, routines}]}. A routine has id, title, anchor (wake, afterBreakfast, afterLunch, afterDinner, time), anchorTime HH:MM, time, recurrence ({kind, weekday, monthDay}), active, remind, createdAt, checkedToday, dueToday, monthCount, totalCount.')]
final class ListRoutinesTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly RoutineSchedule $schedule,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();

            if (null !== $date) {
                return json_encode(RoutineJson::day($this->schedule->day($user, DayPlans::day($date))), JSON_THROW_ON_ERROR);
            }

            return json_encode(['routines' => array_map(RoutineJson::of(...), $this->schedule->describeAll($user))], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
