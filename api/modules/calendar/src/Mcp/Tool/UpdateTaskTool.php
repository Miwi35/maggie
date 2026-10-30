<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\TaskJson;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'update_task', description: 'Update an existing task. Only provided fields will be updated. Set done=true to mark as completed, done=false to reopen. Due date format: YYYY-MM-DD. parentId makes it a subtask of that task (one level only), position sets its order among its siblings. estimatedMinutes (1 to 1440) is how long it should take, firstStep the first small gesture that starts it; both stay in Maggie. dueDate is the deadline (« avant samedi », « pour samedi au plus tard »); scheduledDate (YYYY-MM-DD) is the day the user plans to do it (« mets-la vendredi »), which puts it in that day\'s agenda and today\'s tasks; scheduledMoment is morning, afternoon, evening (« vendredi soir »), or time with scheduledTime HH:MM (« vendredi à 18 h »); reminders, only with time, are minutes before it (0: at the time) — left out when setting a time, one at the time; another moment drops the time and the reminders. To empty an optional field, list its name in clear (description, dueDate, parent — parent makes it a top-level task again —, estimatedMinutes, firstStep, scheduledDate — the whole programmation —, scheduledMoment — the day only stays).')]
class UpdateTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly TaskUrgency $urgency,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    /**
     * @param list<string>|null $clear
     * @param list<int>|null    $reminders
     */
    public function __invoke(
        string $id,
        ?string $title = null,
        ?string $description = null,
        ?string $priority = null,
        ?string $criticality = null,
        ?string $dueDate = null,
        ?bool $done = null,
        ?array $clear = null,
        ?string $parentId = null,
        ?string $position = null,
        ?int $estimatedMinutes = null,
        ?string $firstStep = null,
        ?string $scheduledDate = null,
        ?string $scheduledMoment = null,
        ?string $scheduledTime = null,
        ?array $reminders = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
            $owned = Ulid::isValid($id) ? $this->taskRepository->find($id) : null;
            if (null === $owned || !$owned->getUser()->getId()->equals($user->getId())) {
                return json_encode(['error' => "Task not found: {$id}"], JSON_THROW_ON_ERROR);
            }

            $dueDateObj = null !== $dueDate ? TaskJson::day($dueDate) : null;
            $scheduledDateObj = null !== $scheduledDate ? TaskJson::day($scheduledDate, 'scheduledDate') : null;

            $completedAt = null;
            $clearFields = array_values(array_intersect($clear ?? [], ['description', 'dueDate', 'parent', 'estimatedMinutes', 'firstStep', 'scheduledDate', 'scheduledMoment']));
            if (true === $done) {
                $completedAt = new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris'));
            } elseif (false === $done) {
                $clearFields[] = 'completedAt';
            }

            $envelope = $this->bus->dispatch(new UpdateTaskCommand(
                taskId: $id,
                title: $title,
                description: $description,
                priority: $priority,
                criticality: $criticality,
                dueDate: $dueDateObj,
                completedAt: $completedAt,
                clearFields: $clearFields,
                parentId: $parentId,
                position: $position,
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
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
