<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\TaskCriteria;
use Maggie\Calendar\Mcp\TaskJson;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_tasks', description: 'Gets tasks, filters combined with AND. status: pending (default), done, all. day (YYYY-MM-DD): planned that day, or due that day or before and not done. dueFrom/dueTo, scheduledFrom/scheduledTo: inclusive bounds. dated: false = no due nor planned day, true = one of them. parentId: the subtasks of a task. limit: 50, at most 200. Highest orderScore first; done: most recently done first. Returns {tasks, count, total}; a task has urgency (low, normal, high, critical), orderScore and overdueDays (null if not late).')]
class GetTasksTool
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly McpUserContext $userContext,
        private readonly TaskUrgency $urgency,
    ) {
    }

    public function __invoke(
        string $status = 'pending',
        ?string $day = null,
        ?string $dueFrom = null,
        ?string $dueTo = null,
        ?string $scheduledFrom = null,
        ?string $scheduledTo = null,
        ?bool $dated = null,
        ?string $parentId = null,
        ?int $limit = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
            $criteria = TaskCriteria::fromArguments($status, $day, $dueFrom, $dueTo, $scheduledFrom, $scheduledTo, $dated, $parentId, $limit);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $marked = array_values(array_map($this->urgency->mark(...), $this->taskRepository->findByCriteria($user, $criteria)));
        if ('done' === $criteria->status) {
            usort($marked, static fn ($a, $b): int => $b->getCompletedAt() <=> $a->getCompletedAt());
        } else {
            $marked = $this->urgency->sort($marked);
        }

        $total = count($marked);
        $result = array_map(TaskJson::of(...), array_slice($marked, 0, $criteria->limit));

        return json_encode(['tasks' => $result, 'count' => count($result), 'total' => $total], JSON_THROW_ON_ERROR);
    }
}
