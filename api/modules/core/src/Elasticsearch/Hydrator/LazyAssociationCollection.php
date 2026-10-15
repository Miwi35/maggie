<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Hydrator;

use Doctrine\Common\Collections\AbstractLazyCollection;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\Uid\Ulid;

/**
 * The to-many side of an entity rebuilt from Elasticsearch: the index holds a
 * summary of the lines, not the lines, so they are read from the database the
 * first time something looks at them — a list that is only counted or paged
 * never pays for them.
 *
 * @extends AbstractLazyCollection<int, object>
 */
final class LazyAssociationCollection extends AbstractLazyCollection
{
    /**
     * @param class-string $ownerClass
     */
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly string $ownerClass,
        private readonly Ulid $ownerId,
        private readonly string $property,
    ) {
    }

    protected function doInitialize(): void
    {
        $rows = $this->em->createQuery(\sprintf(
            'SELECT target FROM %1$s target WHERE target.id IN (SELECT linked.id FROM %2$s owner JOIN owner.%3$s linked WHERE owner.id = :id)',
            $this->em->getClassMetadata($this->ownerClass)->getAssociationTargetClass($this->property),
            $this->ownerClass,
            $this->property,
        ))->setParameter('id', $this->ownerId, 'ulid')->getResult();

        /** @var Collection<int, object> $collection */
        $collection = new ArrayCollection($rows);
        $this->collection = $collection;
    }
}
