<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Event\MealRemoved;
use Maggie\Cookbook\Event\MealRescheduled;
use Maggie\Cookbook\Specification\IsMealRemoved;
use Maggie\Cookbook\Specification\IsMealRescheduled;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the events of a meal leaving the agenda or moving in it, and nothing else:
 * the effects live in their handlers.
 *
 * A meal's contributions to the grocery list are erased with it by the database
 * cascade, so what they were is read in onFlush, while they still exist, and
 * travels with the event. The events go out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class MealScheduleListener
{
    /** @var list<MealRemoved|MealRescheduled> */
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
        $em = $args->getObjectManager();
        $uow = $em->getUnitOfWork();

        $deletions = $uow->getScheduledEntityDeletions();
        $removed = new IsMealRemoved(array_values($deletions));

        foreach ($deletions as $entity) {
            if ($removed->isSatisfiedBy($entity)) {
                /* @var Meal $entity */
                $this->pending[] = new MealRemoved(
                    (string) $entity->getId(),
                    (string) $entity->getAgenda()->getUser()->getId(),
                    $this->contributionsOf($em, $entity),
                );
            }
        }

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Meal && (new IsMealRescheduled($uow->getEntityChangeSet($entity)))->isSatisfiedBy($entity)) {
                $this->pending[] = new MealRescheduled((string) $entity->getId());
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

    /**
     * What the meal had put on the list, read while its contributions exist.
     *
     * They are deleted with the meal, through the unit of work: left to the database
     * cascade, a contribution already managed would point at a meal that is gone and
     * make the next flush fail.
     *
     * @return list<array{groceryItemId: string, quantity: float}>
     */
    private function contributionsOf(EntityManagerInterface $em, Meal $meal): array
    {
        $uow = $em->getUnitOfWork();
        $contributions = [];

        foreach ($em->getRepository(MealGroceryContribution::class)->findBy(['meal' => $meal]) as $contribution) {
            $contributions[] = ['groceryItemId' => (string) $contribution->getGroceryItem()->getId(), 'quantity' => $contribution->getQuantity()];

            if (!$uow->isScheduledForDelete($contribution)) {
                $uow->scheduleForDelete($contribution);
            }
        }

        return $contributions;
    }
}
