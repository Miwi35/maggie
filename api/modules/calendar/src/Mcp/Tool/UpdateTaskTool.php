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

#[McpTool(name: 'update_task', description: 'Updates a task; only given fields change. done=true completes, false reopens. Dates YYYY-MM-DD. parentId makes it a subtask (one level), position orders siblings. estimatedMinutes 1 to 1440; firstStep. dueDate: deadline; scheduledDate: planned day. scheduledMoment: morning, afternoon, evening, or time with scheduledTime HH:MM; reminders (with time only): minutes before; another moment drops them. timed=true: timer. rrule: new rule from the current day; moving the day moves this occurrence only. clear: fields to empty among description, dueDate, parent, estimatedMinutes, firstStep, scheduledDate, scheduledMoment, rrule.')]
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
        ?bool $timed = null,
        ?string $rrule = null,
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
            $clearFields = array_values(array_intersect($clear ?? [], ['description', 'dueDate', 'parent', 'estimatedMinutes', 'firstStep', 'scheduledDate', 'scheduledMoment', 'rrule']));
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
                timed: $timed,
                rrule: $rrule,
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
