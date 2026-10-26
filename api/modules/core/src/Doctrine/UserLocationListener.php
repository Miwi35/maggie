<?php

declare(strict_types=1);

namespace Maggie\Core\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Event\UserLocationChangedEvent;
use Maggie\Core\Specification\HasUserLocationChanged;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises UserLocationChangedEvent and nothing else (MAG-650): the effects live in its handlers.
 *
 * The changeset only exists during onFlush; the event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class UserLocationListener
{
    /** @var list<array{string, UserLocationChangedEvent::POSITION|UserLocationChangedEvent::CAR}> */
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
            if (!$entity instanceof UserLocation) {
                continue;
            }

            $changeSet = $uow->getEntityChangeSet($entity);
            foreach ([UserLocationChangedEvent::POSITION, UserLocationChangedEvent::CAR] as $what) {
                if ((new HasUserLocationChanged($changeSet, $what))->isSatisfiedBy($entity)) {
                    $this->changes[] = [(string) $entity->getUser()->getId(), $what];
                }
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $changes = $this->changes;
        $this->changes = [];

        foreach ($changes as [$userId, $what]) {
            // The location is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new UserLocationChangedEvent($userId, $what));
            } catch (\Throwable $e) {
                $this->logger->error('The {what} of user {user} changed but its event failed: {error}', [
                    'what' => $what,
                    'user' => $userId,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
