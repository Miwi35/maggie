<?php

namespace Maggie\Core\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Message\UpdateUserCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<User, User> */
class UpdateUserProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): User
    {
        $envelope = $this->bus->dispatch(new UpdateUserCommand(
            userId: (string) $data->getId(),
            name: $data->getName(),
            avatar: $data->getAvatar(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
