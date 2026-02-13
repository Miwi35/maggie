<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
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

        $envelope = $this->bus->dispatch(new CreateTaskCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            priority: $data->getPriority()->value,
            criticality: $data->getCriticality()->value,
            dueDate: $data->getDueDate(),
            doneDate: $data->getDoneDate(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
