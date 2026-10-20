<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\RoutineJson;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'list_routines', description: 'Read the user\'s routines — small habits that come back (a glass of water at waking, the bins on Tuesday evening). Without date: every routine, active or not, in the order of the day, as {routines: [...]}. With date (YYYY-MM-DD): only the active routines due that day, grouped by moment of the day, as {date, moments: [{anchor, time, routines: [...]}]}, each with checked (ticked that day or not). A routine has id, title, anchor (wake, afterBreakfast, afterLunch, afterDinner or time), anchorTime (HH:MM, only with anchor time), time (the hour it falls at), recurrence ({kind: daily} | {kind: weekdays} | {kind: weekly, weekday: 1 Monday to 7 Sunday} | {kind: monthly, monthDay: 1 to 31}), active, remind, createdAt, checkedToday, dueToday, monthCount (ticks this month) and totalCount (ticks since the creation). Report what was done — the month\'s count, the total — never a streak, never days missed.')]
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
