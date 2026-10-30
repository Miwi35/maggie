<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Exception\InvalidTaskParentException;
use Maggie\Calendar\Exception\InvalidTaskScheduleException;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Task, Task> */
class CreateTaskProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Task
    {
        /** @var User $user */
        $user = $this->security->getUser();

        try {
            $envelope = $this->bus->dispatch(new CreateTaskCommand(
                userId: (string) $user->getId(),
                title: $data->getTitle(),
                description: $data->getDescription(),
                priority: $data->getPriority()->value,
                criticality: $data->getCriticality()->value,
                dueDate: $data->getDueDate(),
                completedAt: $data->getCompletedAt(),
                parentId: null !== $data->getParent() ? (string) $data->getParent()->getId() : null,
                position: $data->getPosition(),
                estimatedMinutes: $data->getEstimatedMinutes(),
                firstStep: $data->getFirstStep(),
                scheduledDate: $data->getScheduledDate(),
                scheduledMoment: $data->getScheduledMoment()?->value,
                scheduledTime: $data->getScheduledTime(),
                // Left out, a precise time gets its default reminder.
                reminders: [] === $data->getReminders() ? null : $data->getReminders(),
            ));
        } catch (HandlerFailedException $e) {
            if ($e->getPrevious() instanceof InvalidTaskParentException || $e->getPrevious() instanceof InvalidTaskScheduleException) {
                throw new BadRequestHttpException($e->getPrevious()->getMessage(), $e);
            }

            throw $e;
        }

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
