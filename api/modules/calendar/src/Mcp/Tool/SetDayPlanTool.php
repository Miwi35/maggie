<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Message\SetDayPlanCommand;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'set_day_plan', description: 'Sets the user\'s day. taskIds is the whole ordered list of the day\'s tasks, 5 at most: it replaces the slots, so keeping a task means listing it, and a task listed in place of another sends the other back to the reserve. A task appears once. date is YYYY-MM-DD, today when left out; a future day is allowed. confirm=false writes a proposal (confirmedAt stays null). Returns the day as get_day_plan does.')]
final class SetDayPlanTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly DayPlans $dayPlans,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @param list<string> $taskIds */
    public function __invoke(array $taskIds, ?string $date = null, bool $confirm = true): string
    {
        try {
            $user = $this->userContext->requireUser();
            $day = null !== $date ? DayPlans::day($date) : $this->dayPlans->today($user);
            $plan = $this->dayPlans->forDate($user, $day);

            $this->bus->dispatch(new SetDayPlanCommand(
                userId: (string) $user->getId(),
                dayPlanId: (string) $plan->getId(),
                slots: array_map(static fn (mixed $taskId): array => ['type' => DayPlan::TYPE_TASK, 'taskId' => $taskId], $taskIds),
                confirm: $confirm,
            ));

            return json_encode(['success' => true, 'day' => $this->dayPlans->describe($plan)], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            // A refused list is Maggie's to read; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
