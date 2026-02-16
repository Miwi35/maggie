<?php

declare(strict_types=1);

namespace Maggie\Notification\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Notification, void> */
class DeleteNotificationProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteNotificationCommand(
            notificationId: (string) $data->getId(),
        ));
    }
}
