<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PostFlushEventArgs;
use Doctrine\ORM\Events;
use Doctrine\ORM\Mapping\ClassMetadata;
use Doctrine\Persistence\ObjectManager;
use Doctrine\Persistence\Proxy;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Entity\User;
use Symfony\Contracts\Service\ResetInterface;

/**
 * Collects, message after message, what the database was told to change.
 *
 * Doctrine forgets a changeset once the flush is over, and a handler flushes
 * several times: this listens to every flush and keeps, for the message being
 * handled, one {@see Change} per row. The {@see ProjectionMiddleware} publishes
 * and indexes them when the root message is done.
 *
 * A flush is only taken into account once it has gone through (`postFlush`):
 * one that failed rolled back, and projecting it would announce rows that do
 * not exist.
 *
 * Messages nest (a handler dispatches another command): each one has its own
 * {@see Work}, which a nested message hands to its parent, so the projection
 * happens once, for the root.
 */
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
class WorkCollector implements ResetInterface
{
    /** @var list<Work> */
    private array $stack = [];

    private ?Work $pending = null;

    /** @var array<class-string, string|null> */
    private array $rootRelations = [];

    public function __construct(
        private readonly IndexMetadataReader $metadataReader,
    ) {
    }

    public function begin(): void
    {
        $this->stack[] = new Work();
    }

    /**
     * Closes the current message's work. A nested message gives it to its
     * parent and returns null; the root message gets it to project.
     */
    public function finish(): ?Work
    {
        $work = array_pop($this->stack);
        if (null === $work) {
            return null;
        }

        $parent = end($this->stack);
        if (false === $parent) {
            return $work;
        }

        $parent->merge($work);

        return null;
    }

    /**
     * The entity a handler returned is announced whether or not a flush touched
     * it. `$actionPayload` replaces its payload (check, reorder…).
     *
     * @param array<string, mixed>|null $actionPayload
     */
    public function returned(MercurePublishable $entity, ?array $actionPayload): void
    {
        $work = end($this->stack);
        if (false === $work) {
            return;
        }

        $change = $this->change($entity, ChangeKind::Updated);
        if (null === $change) {
            return;
        }
        $change->actionPayload = $actionPayload;
        $work->record($change);
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $this->pending = null;
        if ([] === $this->stack) {
            return;
        }

        $em = $args->getObjectManager();
        $uow = $em->getUnitOfWork();
        $pending = new Work();

        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            $this->collect($em, $pending, $entity, ChangeKind::Inserted, []);
        }
        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            $this->collect($em, $pending, $entity, ChangeKind::Updated, array_keys($uow->getEntityChangeSet($entity)));
        }
        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            $this->collect($em, $pending, $entity, ChangeKind::Deleted, []);
        }
        // ManyToMany collections (Meal.recipes): the owner has no changeset of its own.
        foreach ([...$uow->getScheduledCollectionUpdates(), ...$uow->getScheduledCollectionDeletions()] as $collection) {
            $owner = $collection->getOwner();
            if (null !== $owner) {
                $this->collect($em, $pending, $owner, ChangeKind::Updated, [$collection->getMapping()['fieldName']]);
            }
        }

        $this->pending = $pending;
    }

    public function postFlush(PostFlushEventArgs $args): void
    {
        $pending = $this->pending;
        $this->pending = null;

        $work = end($this->stack);
        if (null !== $pending && false !== $work) {
            $work->merge($pending);
        }
    }

    public function reset(): void
    {
        $this->stack = [];
        $this->pending = null;
    }

    /** @param list<string> $properties */
    private function collect(ObjectManager $em, Work $work, object $entity, ChangeKind $kind, array $properties): void
    {
        if ($entity instanceof MercurePublishable || $entity instanceof IndexableInterface) {
            $change = $this->change($entity, $kind);
            if (null !== $change) {
                $change->properties = $properties;
                $work->record($change);
            }
        }

        $root = $this->rootOf($entity);
        if ($root instanceof MercurePublishable || $root instanceof IndexableInterface) {
            // The root's own collection changed: that is what its screens must redraw.
            $change = $this->change($root, ChangeKind::Updated);
            if (null !== $change) {
                $change->properties = $this->rootCollectionOf($em, $entity);
                $work->record($change);
            }
        }
    }

    private function change(object $entity, ChangeKind $kind): ?Change
    {
        if (!method_exists($entity, 'getId')) {
            return null;
        }

        $class = self::realClassOf($entity);
        $isGone = ChangeKind::Deleted === $kind;

        return new Change(
            class: $class,
            id: (string) $entity->getId(),
            kind: $kind,
            ownerId: self::ownerOf($entity),
            entity: $isGone ? null : $entity,
            indices: $isGone && $entity instanceof IndexableInterface ? $this->metadataReader->indicesOf($class) : [],
        );
    }

    /**
     * The real class: a Doctrine proxy would spell topics and indices after its own name.
     *
     * @return class-string
     */
    private static function realClassOf(object $entity): string
    {
        return $entity instanceof Proxy ? get_parent_class($entity) : $entity::class;
    }

    /** @return list<string> the root's property holding this entity, [] when unknown */
    private function rootCollectionOf(ObjectManager $em, object $entity): array
    {
        $relation = $this->rootRelations[self::realClassOf($entity)] ?? null;
        $metadata = null !== $relation ? $em->getClassMetadata(self::realClassOf($entity)) : null;
        $inverse = $metadata instanceof ClassMetadata ? ($metadata->getAssociationMapping($relation)['inversedBy'] ?? null) : null;

        return \is_string($inverse) ? [$inverse] : [];
    }

    private function rootOf(object $entity): ?object
    {
        $class = self::realClassOf($entity);
        if (!\array_key_exists($class, $this->rootRelations)) {
            $attributes = (new \ReflectionClass($class))->getAttributes(AggregateRoot::class);
            $this->rootRelations[$class] = [] === $attributes ? null : $attributes[0]->newInstance()->relation;
        }

        $relation = $this->rootRelations[$class];
        if (null === $relation) {
            return null;
        }

        try {
            $root = $entity->{'get'.ucfirst($relation)}();
        } catch (\Error) {
            return null;
        }

        return \is_object($root) ? $root : null;
    }

    /** The user the screens of this row are scoped to — never the user who happened to trigger the change. */
    public static function ownerOf(object $entity): ?string
    {
        if ($entity instanceof User) {
            return (string) $entity->getId();
        }

        try {
            if ($entity instanceof OwnedByUserInterface) {
                return (string) $entity->getUser()->getId();
            }

            if ($entity instanceof OwnedThroughInterface) {
                $parent = $entity->{'get'.ucfirst($entity::getOwnerRelation())}();

                return $parent instanceof OwnedByUserInterface ? (string) $parent->getUser()->getId() : null;
            }
        } catch (\Error) {
            // A relation not initialized yet: the row has no owner to publish to.
            return null;
        }

        return null;
    }
}
