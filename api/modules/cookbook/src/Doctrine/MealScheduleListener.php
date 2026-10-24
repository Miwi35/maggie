<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Event\MealGuestsChanged;
use Maggie\Cookbook\Event\MealRemoved;
use Maggie\Cookbook\Event\MealRescheduled;
use Maggie\Cookbook\Specification\IsMealRemoved;
use Maggie\Cookbook\Specification\IsMealRescheduled;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the events of a meal leaving the agenda, moving in it or changing its
 * guests, and nothing else: the effects live in their handlers.
 *
 * The events go out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class MealScheduleListener
{
    /** @var list<MealRemoved|MealRescheduled|MealGuestsChanged> */
    private array $pending = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its events must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->pending = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        $deletions = $uow->getScheduledEntityDeletions();
        $removed = new IsMealRemoved(array_values($deletions));

        foreach ($deletions as $entity) {
            if ($removed->isSatisfiedBy($entity)) {
                /* @var Meal $entity */
                $this->pending[] = new MealRemoved(
                    (string) $entity->getId(),
                    (string) $entity->getAgenda()->getUser()->getId(),
                );
            }
        }

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if (!$entity instanceof Meal) {
                continue;
            }

            $changeSet = $uow->getEntityChangeSet($entity);

            if ((new IsMealRescheduled($changeSet))->isSatisfiedBy($entity)) {
                $this->pending[] = new MealRescheduled((string) $entity->getId());
            }

            // Its own number of guests, not the effective one: the default
            // changing is the meal settings' business (MAG-482).
            if (isset($changeSet['guests']) && $changeSet['guests'][0] !== $changeSet['guests'][1]) {
                $this->pending[] = new MealGuestsChanged((string) $entity->getId(), $changeSet['guests'][0]);
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $pending = $this->pending;
        $this->pending = [];

        foreach ($pending as $event) {
            // The meal is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch($event);
            } catch (\Throwable $e) {
                $this->logger->error('Meal {meal} changed but {event} failed: {error}', [
                    'meal' => $event->mealId,
                    'event' => $event::class,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
