<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_task', description: 'Update an existing task. Only provided fields will be updated. Set done=true to mark as completed, done=false to reopen.')]
class UpdateTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $id,
        ?string $title = null,
        ?string $description = null,
        ?string $priority = null,
        ?string $criticality = null,
        ?string $dueDate = null,
        ?bool $done = null,
    ): string {
        try {
            $dueDateObj = $dueDate !== null
                ? new \DateTimeImmutable($dueDate, new \DateTimeZone('Europe/Paris'))
                : null;

            $completedAt = null;
            if ($done === true) {
                $completedAt = new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris'));
            } elseif ($done === false) {
                $completedAt = null;
            }

            $envelope = $this->bus->dispatch(new UpdateTaskCommand(
                taskId: $id,
                title: $title,
                description: $description,
                priority: $priority,
                criticality: $criticality,
                dueDate: $dueDateObj,
                completedAt: $completedAt,
            ));

            /** @var Task $task */
            $task = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'task' => [
                    'id' => (string) $task->getId(),
                    'title' => $task->getTitle(),
                    'priority' => $task->getPriority()->value,
                    'criticality' => $task->getCriticality()->value,
                    'dueDate' => $task->getDueDate()?->format('c'),
                    'isDone' => $task->isDone(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
