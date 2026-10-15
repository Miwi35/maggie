<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\DayPlans;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_day_plan', description: 'Read the user\'s day: at most 5 slots, each a task to do that day, in order. date is YYYY-MM-DD, today (the user\'s timezone) when left out; an empty day is created on first read. Returns slots [{position, type, task}], now (the first slot not done: what to do now, or null), reserve (every other open task that is not a subtask, highest orderScore first — the tasks waiting, not shown on the day), done and total (the ring: done slots out of the slots), confirmedAt (null while the day is only proposed). Each task has id, title, estimatedMinutes, firstStep, completedAt, dueDate, postponedSince (the day it was put off since — say « reportée », never a reproach), urgency and criticality.')]
final class GetDayPlanTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly DayPlans $dayPlans,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $day = null !== $date ? DayPlans::day($date) : $this->dayPlans->today($user);

            return json_encode($this->dayPlans->describe($this->dayPlans->forDate($user, $day)), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
