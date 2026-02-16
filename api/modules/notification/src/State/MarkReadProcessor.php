<?php

declare(strict_types=1);

namespace Maggie\Notification\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Notification, Notification> */
class MarkReadProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Notification
    {
        $envelope = $this->bus->dispatch(new MarkNotificationReadCommand(
            notificationId: (string) $data->getId(),
            readAt: $data->getReadAt(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
