<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'delete_task', description: 'Deletes one of the owner\'s tasks by its id (from get_tasks) for good; a task done is update_task done=true, a routine paused save_routine active=false. Returns a confirmation.')]
class DeleteTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly TaskRepository $taskRepository,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $user = $this->userContext->requireUser();
            $task = Ulid::isValid($id) ? $this->taskRepository->find($id) : null;

            // Another user's task answers exactly like a missing one.
            if (null === $task || !$task->getUser()->getId()->equals($user->getId())) {
                return json_encode(['error' => "Task not found: {$id}"], JSON_THROW_ON_ERROR);
            }

            $this->bus->dispatch(new DeleteTaskCommand(taskId: $id));

            return json_encode([
                'success' => true,
                'message' => 'Task has been deleted.',
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
