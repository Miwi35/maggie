<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Message\DeleteTaskCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'delete_task', description: 'Delete a task by its ID. Returns confirmation of deletion.')]
class DeleteTaskTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $id): string
    {
        try {
            $this->bus->dispatch(new DeleteTaskCommand(taskId: $id));

            return json_encode([
                'success' => true,
                'message' => 'Task has been deleted.',
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
