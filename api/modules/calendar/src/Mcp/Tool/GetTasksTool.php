<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Mcp\TaskJson;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskAlert;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskUrgency;
use Maggie\Calendar\Service\TaskViews;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_tasks', description: 'Gets tasks. status: pending, done, overdue, all; days limits the upcoming scope. Ordered by orderScore descending. view overrides status: today (not done, planned or due today, or late; each followed by its subtasks; plus reminders [{taskId, title, reason: to_schedule|not_done, days, urgency}]), upcoming (not done, due or planned after today), someday (not done, no due or planned day), done (50 most recent; total = count). counts: size of the four views. A task has urgency (low, normal, high, critical), orderScore, overdueDays (null if not late), parentId, position, links, dueDate, scheduledDate, scheduledMoment, scheduledTime HH:MM, reminders.')]
class GetTasksTool
{
    private const DONE_LIMIT = 50;

    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly McpUserContext $userContext,
        private readonly TaskUrgency $urgency,
        private readonly TaskViews $views,
        private readonly TaskAlertSchedule $alertSchedule,
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
            if (!in_array($view, TaskViews::ALL, true)) {
                return json_encode(['error' => "Unknown view: {$view}. Known views: ".implode(', ', TaskViews::ALL).'.'], JSON_THROW_ON_ERROR);
            }

            $read = $this->views->read($user, $view, 1, self::DONE_LIMIT);
            $result = array_map(TaskJson::of(...), $read['tasks']);

            $json = ['view' => $view, 'tasks' => $result, 'count' => count($result), 'total' => $read['total'], 'counts' => $read['counts']];
            if (TaskViews::TODAY === $view) {
                $json['reminders'] = array_map(static fn (TaskAlert $alert): array => [
                    'taskId' => (string) $alert->task->getId(),
                    'title' => $alert->task->getTitle(),
                    'reason' => $alert->reason->digestReason(),
                    'days' => $alert->days,
                    'urgency' => $alert->urgency->value,
                ], $this->alertSchedule->dueAlerts($user, $this->urgency->today($user)));
            }

            return json_encode($json, JSON_THROW_ON_ERROR);
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

        $marked = array_values(array_map($this->urgency->mark(...), $tasks));
        // Done tasks keep the most recently done first; the others go by score.
        $result = array_map(TaskJson::of(...), 'done' === $status ? $marked : $this->urgency->sort($marked));

        return json_encode(['status' => $status, 'tasks' => $result, 'count' => count($result)], JSON_THROW_ON_ERROR);
    }
}
