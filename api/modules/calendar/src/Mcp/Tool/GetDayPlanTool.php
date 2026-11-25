<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\DayPlans;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_day_plan', description: 'The day plan is the ordered short list of tasks chosen for a day, with its reserve; it holds no event (get_events), and every task stays readable with get_tasks. Reads the day: at most 5 slots. date YYYY-MM-DD, today by default. Returns slots [{position, type, task}], now (first slot not done, or null), reserve (other open non-subtasks by orderScore), done, total, confirmedAt (null while proposed). A task has id, title, estimatedMinutes, firstStep, completedAt, dueDate, scheduledDate, postponedSince, urgency, criticality, rrule.')]
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
