<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Message\AnswerTaskReminderCommand;
use Maggie\Calendar\Repository\TaskReminderRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'answer_task_reminder', description: 'Answer a task reminder the user is replying to (its reminderId is given with the message), exactly as the button of the notification would: done (the task is ticked), in10 (a new reminder in ten minutes), tonight (after dinner), tomorrow (into tomorrow\'s five), start (the user begins it now), open. A reminder is answered once, and not every kind accepts every action: a refusal says why. Only for a reminder the user is answering — never to tick a task on your own initiative (use update_task).')]
class AnswerTaskReminderTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly TaskReminderRepository $repository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $reminderId, string $action): string
    {
        try {
            $user = $this->userContext->requireUser();

            if (!\in_array($action, TaskReminder::ACTIONS, true)) {
                return json_encode(['error' => 'action must be one of: '.implode(', ', TaskReminder::ACTIONS)], JSON_THROW_ON_ERROR);
            }
            $reminder = $this->repository->findOwned($user, $reminderId);
            if (null === $reminder) {
                return json_encode(['error' => 'Reminder not found.'], JSON_THROW_ON_ERROR);
            }

            $answer = $this->bus->dispatch(new AnswerTaskReminderCommand((string) $user->getId(), (string) $reminder->getId(), $action))
                ->last(HandledStamp::class)?->getResult();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            // A refusal (answered already, tonight passed, tomorrow full) is the model's to relay; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }

        return json_encode(['success' => true, 'answer' => $answer], JSON_THROW_ON_ERROR);
    }
}
