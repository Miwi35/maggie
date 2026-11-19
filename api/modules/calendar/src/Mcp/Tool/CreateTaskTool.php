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

#[McpTool(name: 'create_task', description: 'Creates a task. priority: low, medium, high. criticality: low, medium, high, critical. Dates YYYY-MM-DD. parentId: a top-level task. estimatedMinutes 1-1440; firstStep. dueDate: deadline; scheduledDate: planned day. scheduledMoment: morning/afternoon/evening, or time with scheduledTime HH:MM; reminders (time only): minutes before, 0 at it. timed=true: timer. rrule (RFC 5545, no "RRULE:", DAILY-YEARLY, UNTIL not COUNT) repeats from scheduledDate, else dueDate, a day of the rule; done or past, the next task takes it.')]
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
        ?bool $timed = null,
        ?string $rrule = null,
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
                timed: $timed ?? false,
                rrule: $rrule,
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
