<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Exception\InvalidTaskParentException;
use Maggie\Calendar\Exception\InvalidTaskScheduleException;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Task, Task> */
class UpdateTaskProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Task
    {
        /** @var Task|null $previous */
        $previous = $context['previous_data'] ?? null;

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if (null !== $previous) {
            foreach (['description' => 'getDescription', 'dueDate' => 'getDueDate', 'completedAt' => 'getCompletedAt', 'parent' => 'getParent', 'estimatedMinutes' => 'getEstimatedMinutes', 'firstStep' => 'getFirstStep', 'scheduledDate' => 'getScheduledDate', 'scheduledMoment' => 'getScheduledMoment'] as $field => $getter) {
                if (null === $data->$getter() && null !== $previous->$getter()) {
                    $clearFields[] = $field;
                }
            }
        }

        $parentId = null !== $data->getParent() ? (string) $data->getParent()->getId() : null;
        $previousParentId = null !== $previous?->getParent() ? (string) $previous->getParent()->getId() : null;

        // Only send fields that actually changed compared to previous state
        $command = new UpdateTaskCommand(
            taskId: (string) $data->getId(),
            title: null === $previous || $data->getTitle() !== $previous->getTitle() ? $data->getTitle() : null,
            description: null === $previous || $data->getDescription() !== $previous->getDescription() ? $data->getDescription() : null,
            priority: null === $previous || $data->getPriority() !== $previous->getPriority() ? $data->getPriority()->value : null,
            criticality: null === $previous || $data->getCriticality() !== $previous->getCriticality() ? $data->getCriticality()->value : null,
            dueDate: null === $previous || $data->getDueDate() != $previous->getDueDate() ? $data->getDueDate() : null,
            completedAt: null === $previous || $data->getCompletedAt() != $previous->getCompletedAt() ? $data->getCompletedAt() : null,
            clearFields: $clearFields,
            parentId: null === $previous || $parentId !== $previousParentId ? $parentId : null,
            position: null === $previous || $data->getPosition() !== $previous->getPosition() ? $data->getPosition() : null,
            estimatedMinutes: null === $previous || $data->getEstimatedMinutes() !== $previous->getEstimatedMinutes() ? $data->getEstimatedMinutes() : null,
            firstStep: null === $previous || $data->getFirstStep() !== $previous->getFirstStep() ? $data->getFirstStep() : null,
            scheduledDate: null === $previous || $data->getScheduledDate() != $previous->getScheduledDate() ? $data->getScheduledDate() : null,
            scheduledMoment: null === $previous || $data->getScheduledMoment() !== $previous->getScheduledMoment() ? $data->getScheduledMoment()?->value : null,
            // A time sent null is no change of its own: the moment says whether one is kept.
            scheduledTime: null === $previous || $data->getScheduledTime() !== $previous->getScheduledTime() ? $data->getScheduledTime() : null,
            reminders: null === $previous || $data->getReminders() !== $previous->getReminders() ? $data->getReminders() : null,
        );

        // The deserializer applied the patch to the managed entity, the parent
        // included — read from the index, so unknown to Doctrine. The command
        // carries every change: start the handler from the stored state.
        $this->em->refresh($data);

        try {
            $envelope = $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            if ($e->getPrevious() instanceof InvalidTaskParentException || $e->getPrevious() instanceof InvalidTaskScheduleException) {
                throw new BadRequestHttpException($e->getPrevious()->getMessage(), $e);
            }

            throw $e;
        }

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
