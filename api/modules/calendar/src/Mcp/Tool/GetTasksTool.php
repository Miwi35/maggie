<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\TaskJson;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Service\TodayTasks;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_tasks', description: 'Get tasks filtered by status. Status: pending (not done), done, overdue (past due date and not done), all. Optional days parameter limits upcoming scope. View "today" (instead of status) is the Today list: tasks not done that are due today or late, the most overdue first, each followed by its subtasks. Each task has overdueDays (days late, null when not late) and parentId (the task it is a subtask of, or null), position (its order among its siblings, compared as a string), links (read from Google) and dueDate as YYYY-MM-DD.')]
class GetTasksTool
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly McpUserContext $userContext,
        private readonly TaskOverdue $overdue,
        private readonly TodayTasks $todayTasks,
    ) {
    }

    public function __invoke(string $status = 'pending', ?int $days = null, ?string $view = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        if (null !== $view) {
            if ('today' !== $view) {
                return json_encode(['error' => "Unknown view: {$view}. Known views: today."], JSON_THROW_ON_ERROR);
            }

            $result = array_map(TaskJson::of(...), $this->todayTasks->for($user));

            return json_encode(['view' => $view, 'tasks' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
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

        $result = array_map(fn ($task) => TaskJson::of($this->overdue->mark($task)), $tasks);

        return json_encode(['status' => $status, 'tasks' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
