<?php

declare(strict_types=1);

namespace Maggie\Calendar\Home;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Doctrine\Persistence\Proxy;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardPublisher;
use Psr\Log\LoggerInterface;

/**
 * Tells the Accueil that the Agenda card moved (MAG-562), whichever door saved the event:
 * the API, Maggie, a Google sync. An agenda renamed, recoloured or removed changes the card too.
 *
 * Only plain events: a meal or a proaction is another module's row and is not on the card.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class RefreshAgendaHomeCard
{
    /** @var array<string, User> by user id */
    private array $users = [];

    public function __construct(
        private readonly HomeCardPublisher $publisher,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its users must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->users = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates(), ...$uow->getScheduledEntityDeletions()] as $entity) {
            $agenda = match (true) {
                $entity instanceof Agenda => $entity,
                $entity instanceof Event && Event::class === ($entity instanceof Proxy ? get_parent_class($entity) : $entity::class) => $entity->getAgenda(),
                default => null,
            };
            if (null !== $agenda) {
                $user = $agenda->getUser();
                $this->users[(string) $user->getId()] = $user;
            }
        }
    }

    public function postFlush(): void
    {
        // Forget before publishing: a listener of the publication may flush again.
        $users = $this->users;
        $this->users = [];

        foreach ($users as $user) {
            // The rows are stored: a signal that cannot be sent must not turn the save into an error.
            try {
                $this->publisher->publish('agenda', $user);
            } catch (\Throwable $e) {
                $this->logger->error('Agenda home card refresh failed: {error}', ['error' => $e->getMessage(), 'exception' => $e]);
            }
        }
    }
}
