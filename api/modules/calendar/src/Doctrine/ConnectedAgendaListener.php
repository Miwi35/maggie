<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Event\ConnectedAgendaRenamed;
use Maggie\Calendar\Specification\IsConnectedAgendaRenamed;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the events of a Google-connected agenda's life and nothing else: the effects live in their handlers.
 *
 * Whichever door saves the agenda (admin, mobile, Maggie), the rename follows. The changeset only exists
 * during onFlush; the event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class ConnectedAgendaListener
{
    /** @var list<array{Agenda, array<string, array{mixed, mixed}>}> */
    private array $changes = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its changes must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->changes = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates()] as $entity) {
            if ($entity instanceof Agenda) {
                $changeSet = $uow->getEntityChangeSet($entity);
                if (isset($changeSet['name'])) {
                    $this->changes[] = [$entity, $changeSet];
                }
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again (undoing a refused rename does): forget what is being dispatched before it does.
        $changes = $this->changes;
        $this->changes = [];

        foreach ($changes as [$agenda, $changeSet]) {
            if (!(new IsConnectedAgendaRenamed($changeSet))->isSatisfiedBy($agenda)) {
                continue;
            }

            // The agenda is already stored: a failing effect must not turn its save into an error.
            // Telling Google is reported through the agenda itself (Agenda::getRenameRefusal()).
            try {
                $this->eventBus->dispatch(new ConnectedAgendaRenamed((string) $agenda->getId(), (string) $changeSet['name'][0]));
            } catch (\Throwable $e) {
                $this->logger->error('Agenda {agenda} was renamed but its event failed: {error}', [
                    'agenda' => (string) $agenda->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
