<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Specification\IsMealRemoved;
use Maggie\MealGrocery\Entity\MealGroceryContribution;
use Maggie\MealGrocery\Entity\ShortageAlert;
use Maggie\MealGrocery\Service\ShortageAlerts;
use Maggie\Notification\Entity\Notification;

/**
 * Remembers what a meal had given the grocery side, at the moment it is deleted.
 *
 * Cookbook raises `MealRemoved` knowing nothing of groceries. Contributions and
 * shortage alerts are erased with the meal by the database cascade, so what they
 * were is read in onFlush, while they still exist, and handed over when the event
 * arrives ({@see take()}). They are deleted through the unit of work: left to the
 * cascade, an entity already managed would point at a meal that is gone and make
 * the next flush fail.
 *
 * Nothing clears the ledger on preFlush: a handler of the first event may flush
 * again before the next meal's event arrives. A meal is removed once, and its
 * entry is overwritten if a failed flush left one behind.
 */
#[AsDoctrineListener(event: Events::onFlush)]
final class MealRemovalLedger
{
    /** @var array<string, array{contributions: list<array{groceryItemId: string, quantity: float, unit: ?string}>, notificationIds: list<string>}> */
    private array $removed = [];

    public function onFlush(OnFlushEventArgs $args): void
    {
        $em = $args->getObjectManager();
        $uow = $em->getUnitOfWork();

        $deletions = array_values($uow->getScheduledEntityDeletions());
        $removed = new IsMealRemoved($deletions);

        foreach ($deletions as $meal) {
            if (!$meal instanceof Meal || !$removed->isSatisfiedBy($meal)) {
                continue;
            }

            $contributions = [];
            foreach ($em->getRepository(MealGroceryContribution::class)->findBy(['meal' => $meal]) as $contribution) {
                $contributions[] = [
                    'groceryItemId' => (string) $contribution->getGroceryItem()->getId(),
                    'quantity' => $contribution->getQuantity(),
                    'unit' => $contribution->getUnit()?->value,
                ];

                if (!$uow->isScheduledForDelete($contribution)) {
                    $uow->scheduleForDelete($contribution);
                }
            }

            $notificationIds = [];
            foreach ($em->getRepository(ShortageAlert::class)->findBy(['meal' => $meal]) as $alert) {
                $notification = $em->getRepository(Notification::class)->findOneBy([
                    'user' => $alert->getUser(),
                    'relatedEntityIri' => ShortageAlerts::iri($alert),
                ]);
                if (null !== $notification) {
                    $notificationIds[] = (string) $notification->getId();
                }

                if (!$uow->isScheduledForDelete($alert)) {
                    $uow->scheduleForDelete($alert);
                }
            }

            $this->removed[(string) $meal->getId()] = ['contributions' => $contributions, 'notificationIds' => $notificationIds];
        }
    }

    /**
     * What the meal had given, handed over once.
     *
     * @return array{contributions: list<array{groceryItemId: string, quantity: float, unit: ?string}>, notificationIds: list<string>}
     */
    public function take(string $mealId): array
    {
        $entry = $this->removed[$mealId] ?? ['contributions' => [], 'notificationIds' => []];
        unset($this->removed[$mealId]);

        return $entry;
    }
}
