<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\DayProposal;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'prepare_day', description: 'Propose the user\'s day for the morning, writing nothing: up to 5 tasks chosen by a simple rule — slots already written for the day are kept, then the tasks put off (« reportée »), due that day, urgent, due the next day, the others by score — filled while their estimated minutes (15 when unknown) fit the free time of the agenda between the morning time and 21:00 (or now, when later), at least one, shortest first. date is YYYY-MM-DD, today (the user\'s timezone) when left out. Returns slots [{position, type, reason, minutes, task}], reserveRanked (the other open tasks, best first, to swap in: [{reason, minutes, task}]), totalMinutes and freeMinutes. reason is postponed, dueToday, urgent, dueTomorrow, quick or whenever. To settle the proposal call set_day_plan with the taskIds of the slots; nothing is written until then.')]
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
