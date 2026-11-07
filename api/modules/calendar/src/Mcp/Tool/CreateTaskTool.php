<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\TaskJson;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_task', description: 'Create a new task. Priority: low/medium/high. Criticality: low/medium/high/critical. Due date format: YYYY-MM-DD. To make it a subtask, give parentId — the id of a task that is not a subtask itself (one level only). estimatedMinutes (1 to 1440) is how long it should take, firstStep the first small gesture that starts it; both stay in Maggie, Google never sees them. dueDate is the deadline (« avant samedi », « pour samedi au plus tard »); scheduledDate (YYYY-MM-DD) is the day the user plans to do it (« mets-la vendredi »), which puts it in that day\'s agenda and today\'s tasks; scheduledMoment is morning, afternoon, evening, or time with scheduledTime HH:MM (« vendredi à 18 h »); reminders, only with time, are minutes before it (0: at the time) — left out, one at the time.')]
class CreateTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly TaskUrgency $urgency,
    ) {
    }

    /** @param list<int>|null $reminders */
    public function __invoke(
        string $title,
        ?string $description = null,
        string $priority = 'medium',
        string $criticality = 'low',
        ?string $dueDate = null,
        ?string $parentId = null,
        ?int $estimatedMinutes = null,
        ?string $firstStep = null,
        ?string $scheduledDate = null,
        ?string $scheduledMoment = null,
        ?string $scheduledTime = null,
        ?array $reminders = null,
    ): string {
        try {
            $user = $this->userContext->getUser();
            if (null === $user) {
                return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
            }

            $dueDateObj = null !== $dueDate ? TaskJson::day($dueDate) : null;
            $scheduledDateObj = null !== $scheduledDate ? TaskJson::day($scheduledDate, 'scheduledDate') : null;

            $envelope = $this->bus->dispatch(new CreateTaskCommand(
                userId: (string) $user->getId(),
                title: $title,
                description: $description,
                priority: $priority,
                criticality: $criticality,
                dueDate: $dueDateObj,
                parentId: $parentId,
                estimatedMinutes: $estimatedMinutes,
                firstStep: $firstStep,
                scheduledDate: $scheduledDateObj,
                scheduledMoment: $scheduledMoment,
                scheduledTime: $scheduledTime,
                reminders: $reminders,
            ));

            /** @var Task $task */
            $task = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'task' => TaskJson::of($this->urgency->mark($task)),
            ], JSON_THROW_ON_ERROR);
        } catch (\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
