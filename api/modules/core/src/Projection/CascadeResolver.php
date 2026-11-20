<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Mapping\ManyToManyOwningSideMapping;
use Doctrine\ORM\Mapping\ToOneOwningSideMapping;
use Doctrine\Persistence\Proxy;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Symfony\Contracts\Service\ResetInterface;

/**
 * The rows the database changes on its own when a row is deleted.
 *
 * `ON DELETE CASCADE` removes the children, `ON DELETE SET NULL` empties the
 * column that pointed at the row, and a ManyToMany join row goes with the row
 * it joined: Doctrine knows nothing of it, so nothing would be announced. The
 * mapping tells which associations do so; for a row about to be deleted this
 * reads the rows concerned (one query per such association, none when the
 * class is cited by no cascade) so they are recorded like any other change.
 */
final class CascadeResolver implements ResetInterface
{
    /** @var array<class-string, list<array{child: class-string, field: string, kind: ChangeKind, many: bool}>>|null by the class deleted */
    private ?array $map = null;

    /**
     * Every row the deletion of `$deleted` takes with it (recursively) or changes,
     * limited to the rows that are published or indexed.
     *
     * @return list<array{object, ChangeKind, list<string>}>
     */
    public function cascadesOf(EntityManagerInterface $em, object $deleted): array
    {
        $found = [];
        $seen = [];
        $this->walk($em, $deleted, $found, $seen);

        return $found;
    }

    public function reset(): void
    {
        $this->map = null;
    }

    /**
     * @param list<array{object, ChangeKind, list<string>}> $found
     * @param array<string, true>                           $seen
     */
    private function walk(EntityManagerInterface $em, object $deleted, array &$found, array &$seen): void
    {
        $class = $deleted instanceof Proxy ? get_parent_class($deleted) : $deleted::class;
        $map = $this->map($em);
        $metadata = $em->getClassMetadata($class);
        $idField = $metadata->getSingleIdentifierFieldName();
        $id = $metadata->getIdentifierValues($deleted)[$idField];
        $idType = $metadata->getTypeOfField($idField);

        foreach ([$class, ...class_parents($class)] as $target) {
            foreach ($map[$target] ?? [] as $association) {
                $dql = $association['many']
                    ? \sprintf('SELECT c FROM %s c WHERE :deleted MEMBER OF c.%s', $association['child'], $association['field'])
                    : \sprintf('SELECT c FROM %s c WHERE c.%s = :deleted', $association['child'], $association['field']);

                foreach ($em->createQuery($dql)->setParameter('deleted', $id, $idType)->getResult() as $row) {
                    $isGone = ChangeKind::Deleted === $association['kind'];
                    $key = ($row instanceof Proxy ? get_parent_class($row) : $row::class).'#'.($isGone ? 'gone' : $association['field']).'#'.$this->idOf($em, $row);
                    if (isset($seen[$key])) {
                        continue;
                    }
                    $seen[$key] = true;

                    if ($row instanceof MercurePublishable || $row instanceof IndexableInterface) {
                        $found[] = [$row, $association['kind'], $isGone ? [] : [$association['field']]];
                    }
                    if ($isGone) {
                        $this->walk($em, $row, $found, $seen);
                    }
                }
            }
        }
    }

    private function idOf(EntityManagerInterface $em, object $row): string
    {
        return (string) json_encode(array_map('strval', $em->getUnitOfWork()->getEntityIdentifier($row)));
    }

    /** @return array<class-string, list<array{child: class-string, field: string, kind: ChangeKind, many: bool}>> */
    private function map(EntityManagerInterface $em): array
    {
        if (null !== $this->map) {
            return $this->map;
        }

        $factory = $em->getMetadataFactory();
        $map = [];
        foreach ($factory->getAllMetadata() as $metadata) {
            if ($metadata->isMappedSuperclass || $metadata->isEmbeddedClass) {
                continue;
            }

            foreach ($metadata->associationMappings as $field => $mapping) {
                // An association of a parent entity is read from the parent: the query on it reaches the subclasses.
                $declared = $mapping->declared ?? $metadata->name;
                if ($declared !== $metadata->name && !$factory->getMetadataFor($declared)->isMappedSuperclass) {
                    continue;
                }

                $association = null;
                if ($mapping instanceof ToOneOwningSideMapping) {
                    foreach ($mapping->joinColumns as $column) {
                        $kind = match (strtoupper((string) $column->onDelete)) {
                            'CASCADE' => ChangeKind::Deleted,
                            'SET NULL' => ChangeKind::Updated,
                            default => null,
                        };
                        if (null !== $kind) {
                            $association = ['child' => $metadata->name, 'field' => $field, 'kind' => $kind, 'many' => false];
                            break;
                        }
                    }
                } elseif ($mapping instanceof ManyToManyOwningSideMapping) {
                    foreach ($mapping->joinTable->inverseJoinColumns as $column) {
                        if ('CASCADE' === strtoupper((string) $column->onDelete)) {
                            $association = ['child' => $metadata->name, 'field' => $field, 'kind' => ChangeKind::Updated, 'many' => true];
                            break;
                        }
                    }
                }

                if (null !== $association) {
                    $map[$mapping->targetEntity][] = $association;
                }
            }
        }

        return $this->map = $this->withoutUselessQueries($em, $map);
    }

    /**
     * A child that is neither published nor indexed, nor cited by another cascade,
     * is not worth a query: nothing would come of it.
     *
     * @param array<class-string, list<array{child: class-string, field: string, kind: ChangeKind, many: bool}>> $map
     *
     * @return array<class-string, list<array{child: class-string, field: string, kind: ChangeKind, many: bool}>>
     */
    private function withoutUselessQueries(EntityManagerInterface $em, array $map): array
    {
        $isAnnounced = static function (string $class) use ($em, $map): bool {
            /** @var class-string $class */
            $family = [$class, ...$em->getClassMetadata($class)->subClasses];
            foreach ($family as $member) {
                if (is_subclass_of($member, MercurePublishable::class) || is_subclass_of($member, IndexableInterface::class) || isset($map[$member])) {
                    return true;
                }
            }

            return false;
        };

        foreach ($map as $target => $associations) {
            $map[$target] = array_values(array_filter(
                $associations,
                static fn (array $association) => $isAnnounced($association['child']),
            ));
        }

        return array_filter($map);
    }
}
