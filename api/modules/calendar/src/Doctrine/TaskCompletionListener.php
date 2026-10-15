<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Event\TaskReopened;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises {@see TaskCompleted} and {@see TaskReopened} on every transition of
 * a stored task (MAG-497), whichever door saves it. A task created already
 * done — an old one a Google sync brings in — is no transition. The changeset
 * only exists during onFlush; the events go out after the flush.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class TaskCompletionListener
{
    /** @var list<TaskCompleted|TaskReopened> */
    private array $events = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its events must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->events = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if (!$entity instanceof Task) {
                continue;
            }
            $changeSet = $uow->getEntityChangeSet($entity);
            if (!isset($changeSet['completedAt'])) {
                continue;
            }
            $before = $changeSet['completedAt'][0];
            $after = $changeSet['completedAt'][1];
            if (null === $before && $after instanceof \DateTimeImmutable) {
                $this->events[] = new TaskCompleted((string) $entity->getId(), (string) $entity->getUser()->getId(), $after);
            } elseif (null !== $before && null === $after) {
                $this->events[] = new TaskReopened((string) $entity->getId(), (string) $entity->getUser()->getId());
            }
        }
    }

    public function postFlush(): void
    {
        // A listener may flush again: forget what is being dispatched before it does.
        $events = $this->events;
        $this->events = [];

        foreach ($events as $event) {
            // The task is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch($event);
            } catch (\Throwable $e) {
                $this->logger->error('Task {task} changed state but its event failed: {error}', [
                    'task' => $event->taskId,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
