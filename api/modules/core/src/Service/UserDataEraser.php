<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Identifier\CanonicalId;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Deletes every row a user owns, whatever the module. The roots are the entity
 * classes with a to-one association to User; removing them through the ORM lets
 * the cascades and the commit order deal with the foreign keys. The User row
 * itself stays.
 */
final class UserDataEraser
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly IndexableEntityRegistry $registry,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return array<string, int> rows deleted (or that would be) per entity class */
    public function erase(User $user, bool $dryRun): array
    {
        foreach ($this->entityManager->getMetadataFactory()->getAllMetadata() as $meta) {
            if ($meta->isMappedSuperclass || $meta->rootEntityName !== $meta->getName() || User::class === $meta->getName()) {
                continue;
            }

            foreach ($meta->associationMappings as $field => $mapping) {
                if (User::class === $mapping->targetEntity && $mapping->isToOne()) {
                    foreach ($this->entityManager->getRepository($meta->getName())->findBy([$field => $user]) as $entity) {
                        $this->entityManager->remove($entity);
                    }
                }
            }
        }

        // Cascade-removed children are scheduled too.
        $doomed = array_values($this->entityManager->getUnitOfWork()->getScheduledEntityDeletions());

        $counts = [];
        foreach ($doomed as $entity) {
            $class = $this->entityManager->getClassMetadata($entity::class)->getName();
            $counts[$class] = ($counts[$class] ?? 0) + 1;
        }
        ksort($counts);

        if ($dryRun) {
            $this->entityManager->clear();

            return $counts;
        }

        $this->entityManager->flush();

        foreach ($doomed as $entity) {
            if (!$entity instanceof IndexableInterface) {
                continue;
            }
            // A Meal is an Event: it lives in both indices, a missing document is tolerated.
            foreach ($this->registry->getAll() as $index => $class) {
                if ($entity instanceof $class) {
                    $this->bus->dispatch(new DeleteDocumentCommand($index, CanonicalId::of((string) $entity->getId())));
                }
            }
        }

        return $counts;
    }
}
