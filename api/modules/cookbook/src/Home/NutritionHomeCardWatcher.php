<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Home;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PostFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardPublisher;
use Maggie\Grocery\Entity\Product;
use Psr\Log\LoggerInterface;
use Symfony\Contracts\Service\ResetInterface;

/**
 * Tells the home page that the Nutrition card moved (MAG-564): a meal created,
 * changed or removed — its recipes included — or a recipe or a product whose
 * stock state changed, which is what « manque » on a meal reads.
 *
 * Collected while Doctrine flushes and published once it has gone through, once
 * per user. Not watched: a recipe's ingredient lines and a recipe's deletion, so
 * « manque » can wait for the next refetch after those.
 */
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
class NutritionHomeCardWatcher implements ResetInterface
{
    /** @var array<string, User> by user id */
    private array $pending = [];

    public function __construct(
        private readonly HomeCardPublisher $publisher,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof Meal) {
                $this->note($entity);
            }
        }
        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Meal || $entity instanceof Recipe) {
                $this->note($entity);
            } elseif ($entity instanceof Product && \array_key_exists('stockState', $uow->getEntityChangeSet($entity))) {
                $this->note($entity);
            }
        }
        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Meal) {
                $this->note($entity);
            }
        }
        // Meal.recipes is a ManyToMany: swapping a recipe leaves the meal without a changeset.
        foreach ([...$uow->getScheduledCollectionUpdates(), ...$uow->getScheduledCollectionDeletions()] as $collection) {
            $owner = $collection->getOwner();
            if ($owner instanceof Meal) {
                $this->note($owner);
            }
        }
    }

    public function postFlush(PostFlushEventArgs $args): void
    {
        $users = $this->pending;
        $this->pending = [];

        foreach ($users as $user) {
            try {
                $this->publisher->publish('nutrition', $user);
            } catch (\Throwable $e) {
                // The write is done: a refresh that does not go out is not worth failing it.
                $this->logger->error('Failed to publish the Nutrition home card refresh: {error}', ['error' => $e->getMessage()]);
            }
        }
    }

    public function reset(): void
    {
        $this->pending = [];
    }

    private function note(object $entity): void
    {
        $user = $entity instanceof Meal ? $entity->getAgenda()->getUser() : $entity->getUser();
        $this->pending[(string) $user->getId()] = $user;
    }
}
