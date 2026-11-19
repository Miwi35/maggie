<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\DayProposal;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'prepare_day', description: 'Proposes the day, writing nothing: up to 5 tasks, as many as fit the agenda\'s free time (estimatedMinutes, 15 if unknown), at least one. date is YYYY-MM-DD, today by default. Returns slots [{position, type, reason, minutes, task}], reserveRanked [{reason, minutes, task}], totalMinutes, freeMinutes. reason: scheduled, postponed, dueToday, urgent, dueTomorrow, quick or whenever. Stored by set_day_plan.')]
final class PrepareDayTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly DayPlans $dayPlans,
        private readonly DayProposal $proposal,
    ) {
    }

    public function __invoke(?string $date = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $day = null !== $date ? DayPlans::day($date) : $this->dayPlans->today($user);

            return json_encode($this->proposal->propose($user, $day), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
