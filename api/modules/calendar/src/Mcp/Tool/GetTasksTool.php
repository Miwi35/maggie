<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_tasks', description: 'Get tasks filtered by status. Status: pending (not done), done, overdue (past due date and not done), all. Optional days parameter limits upcoming scope.')]
class GetTasksTool
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
    ) {
    }

    public function __invoke(string $status = 'pending', ?int $days = null): string
    {
        $tasks = match ($status) {
            'pending' => $days !== null
                ? $this->taskRepository->findUpcoming($days)
                : $this->taskRepository->findPending(),
            'done' => $this->taskRepository->createQueryBuilder('t')
                ->where('t.doneDate IS NOT NULL')
                ->orderBy('t.doneDate', 'DESC')
                ->getQuery()
                ->getResult(),
            'overdue' => $this->taskRepository->findOverdue(),
            'all' => $this->taskRepository->findBy([], ['dueDate' => 'ASC']),
            default => $this->taskRepository->findPending(),
        };

        $result = array_map(fn (Task $task) => [
            'id' => (string) $task->getId(),
            'name' => $task->getName(),
            'description' => $task->getDescription(),
            'priority' => $task->getPriority()->value,
            'criticality' => $task->getCriticality()->value,
            'dueDate' => $task->getDueDate()?->format('c'),
            'isDone' => $task->isDone(),
            'doneDate' => $task->getDoneDate()?->format('c'),
        ], $tasks);

        return json_encode(['status' => $status, 'tasks' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
