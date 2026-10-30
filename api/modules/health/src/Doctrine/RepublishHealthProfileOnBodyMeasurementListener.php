<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Repository\HealthProfileRepository;
use Maggie\Health\Service\HealthProfileReader;

/**
 * The profile's weight is the latest weighing not set aside (MAG-707): a
 * weighing recorded, edited, set aside or removed — through any door — may
 * move it, and the basal metabolism and the day's need with it, so the
 * profile is published again for the screens that show it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class RepublishHealthProfileOnBodyMeasurementListener
{
    /** @var array<string, User> the owners whose weighings this flush touches, by user id */
    private array $owners = [];

    public function __construct(
        private readonly HealthProfileRepository $profiles,
        private readonly HealthProfileReader $reader,
        private readonly WorkCollector $collector,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: what it noted is forgotten. */
    public function preFlush(): void
    {
        $this->owners = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([
            ...$uow->getScheduledEntityInsertions(),
            ...$uow->getScheduledEntityUpdates(),
            ...$uow->getScheduledEntityDeletions(),
        ] as $entity) {
            if ($entity instanceof BodyMeasurement) {
                $owner = $entity->getUser();
                $this->owners[(string) $owner->getId()] = $owner;
            }
        }
    }

    public function postFlush(): void
    {
        $owners = $this->owners;
        $this->owners = [];

        foreach ($owners as $owner) {
            // A user who never wrote a profile has nothing on screen to redraw.
            $profile = $this->profiles->findOneByUser($owner);
            if (null !== $profile) {
                $this->collector->touched($this->reader->observe($profile), ['weightKg']);
            }
        }
    }
}
