<?php

declare(strict_types=1);

namespace Maggie\Notification\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\CreateNotificationCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Notification, Notification|null> */
class CreateNotificationProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): ?Notification
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateNotificationCommand(
            type: $data->getType()->value,
            title: $data->getTitle(),
            body: $data->getBody(),
            relatedEntityIri: $data->getRelatedEntityIri(),
            userId: (string) $user->getId(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
