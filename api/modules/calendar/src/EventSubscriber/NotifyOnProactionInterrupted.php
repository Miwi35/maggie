<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\ProactionInterrupted;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Notification\Message\CreateNotificationCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * An interruption is one sentence and a link to the message Maggie left in the thread (MAG-886):
 * a `proaction` notification, whereas a plain message is `message` and never interrupts.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class NotifyOnProactionInterrupted
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly ProactionRepository $proactions,
    ) {
    }

    public function __invoke(ProactionInterrupted $event): void
    {
        $proaction = $this->proactions->findOneForUser($event->proactionId, null);
        if (null === $proaction) {
            return;
        }

        $this->commandBus->dispatch(new CreateNotificationCommand(
            type: 'proaction',
            title: 'Maggie',
            body: $proaction->getNotice(),
            relatedEntityIri: $proaction->getMessageLink(),
            userId: (string) $proaction->getAgenda()->getUser()->getId(),
        ));
    }
}
