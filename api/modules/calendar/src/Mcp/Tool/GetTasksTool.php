<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_tasks', description: 'Get tasks filtered by status. Status: pending (not done), done, overdue (past due date and not done), all. Optional days parameter limits upcoming scope.')]
class GetTasksTool
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $status = 'pending', ?int $days = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $tasks = match ($status) {
            'pending' => null !== $days
                ? $this->taskRepository->findUpcoming($user, $days)
                : $this->taskRepository->findPending($user),
            'done' => $this->taskRepository->findDone($user),
            'overdue' => $this->taskRepository->findOverdue($user),
            'all' => $this->taskRepository->findAllForUser($user),
            default => $this->taskRepository->findPending($user),
        };

        $result = array_map(fn (Task $task) => [
            'id' => (string) $task->getId(),
            'title' => $task->getTitle(),
            'description' => $task->getDescription(),
            'priority' => $task->getPriority()->value,
            'criticality' => $task->getCriticality()->value,
            'dueDate' => $task->getDueDate()?->format('c'),
            'isDone' => $task->isDone(),
            'completedAt' => $task->getCompletedAt()?->format('c'),
        ], $tasks);

        return json_encode(['status' => $status, 'tasks' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
