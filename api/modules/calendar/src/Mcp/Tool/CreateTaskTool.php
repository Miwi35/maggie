<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_task', description: 'Create a new task. Priority: low/medium/high. Criticality: low/medium/high/critical. Due date format: YYYY-MM-DD.')]
class CreateTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $title,
        ?string $description = null,
        string $priority = 'medium',
        string $criticality = 'low',
        ?string $dueDate = null,
    ): string {
        try {
            $user = $this->userContext->getUser();
            if (null === $user) {
                return json_encode(['error' => MissingMcpUserException::MESSAGE], JSON_THROW_ON_ERROR);
            }

            $dueDateObj = null !== $dueDate
                ? new \DateTimeImmutable($dueDate, new \DateTimeZone('Europe/Paris'))
                : null;

            $envelope = $this->bus->dispatch(new CreateTaskCommand(
                userId: (string) $user->getId(),
                title: $title,
                description: $description,
                priority: $priority,
                criticality: $criticality,
                dueDate: $dueDateObj,
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
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
