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

#[McpTool(name: 'get_tasks', description: 'Get tasks filtered by status. Status: pending (not done), done, overdue (past due date and not done), all. Optional days parameter limits upcoming scope. Tasks come highest orderScore first (done: most recently done first): cite them in that order. A view (given, status is ignored) is one tab of the task screen, each task in exactly one: "today" = not done, planned today (scheduledDate) or due today or late — the planned ones first by moment then time, then the others by orderScore —, each followed by its subtasks, and reminders: the day\'s list the morning digest tells, each {taskId, title, reason, days, urgency} — reason "to_schedule" (due in days days, 0 the due day, and no day planned yet: suggest planning it) or "not_done" (days late); "upcoming" = not done, due or planned after today, by day then orderScore; "someday" = not done, no due day nor day planned ahead, by priority then position; "done" = the 50 most recently done (total gives how many). counts gives the size of the four views. Use view "today" whenever the user asks about their day, their tasks or what is on: the late tasks and the urgent ones come first, already ranked by orderScore, so cite them in that order — late (overdueDays) before the others. Use view "someday" only to suggest a task to plan on a light day (few events, no high or critical urgency in today): it has no due day, so it is never late and never urgent by itself. Each task has urgency (low, normal, high, critical — computed from criticality and the due day, never set), orderScore (time pressure + criticality + priority), overdueDays (days late, null when not late) and parentId (the task it is a subtask of, or null), position (its order among its siblings, compared as a string), links (read from Google), dueDate (the deadline) as YYYY-MM-DD, and its programmation: scheduledDate (the day planned, YYYY-MM-DD), scheduledMoment (morning, afternoon, evening, or time with scheduledTime HH:MM) and reminders (minutes before the time).')]
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
