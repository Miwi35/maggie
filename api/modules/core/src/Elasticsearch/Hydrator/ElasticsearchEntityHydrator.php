<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Hydrator;

use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Mapping\ManyToMany;
use Doctrine\ORM\Mapping\OneToMany;
use Doctrine\ORM\Mapping\OneToOne;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\SearchDocumentValue;
use Symfony\Component\Uid\Ulid;

final class ElasticsearchEntityHydrator
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * What reflection says about each class, read once: a page rebuilds up to
     * 100 entities of one class (MAG-544).
     *
     * @var array<class-string, array{
     *     class: \ReflectionClass<object>,
     *     properties: array<string, array{property: \ReflectionProperty, type: ?string}>,
     *     collections: list<string>,
     *     inverseOneToOnes: array<string, string>,
     *     relations: array<string, array{property: string, targetEntity: class-string}>,
     * }>
     */
    private array $plans = [];

    /**
     * @param array<string, mixed> $source      ES _source document
     * @param class-string         $entityClass
     */
    public function hydrate(array $source, string $entityClass): object
    {
        $plan = $this->plans[$entityClass] ??= $this->plan($entityClass);
        $entity = $plan['class']->newInstanceWithoutConstructor();

        // Set the ID
        if (isset($source['id'], $plan['properties']['id'])) {
            $plan['properties']['id']['property']->setValue($entity, Ulid::fromString($source['id']));
        }

        // The to-many sides are not in the document (or only as a summary of
        // the lines): they are read from the database when something looks.
        if (isset($source['id'])) {
            foreach ($plan['collections'] as $propName) {
                $plan['properties'][$propName]['property']->setValue($entity, new LazyAssociationCollection($this->em, $entity, Ulid::fromString($source['id']), $propName));
            }
        }

        // The inverse side of a one-to-one is not in the document either.
        if (isset($source['id'])) {
            foreach ($plan['inverseOneToOnes'] as $propName => $owningSide) {
                $plan['properties'][$propName]['property']->setValue($entity, $this->inverseOneToOne($entity, $entityClass, $propName, $owningSide, Ulid::fromString($source['id'])));
            }
        }

        // Set all other fields from source
        foreach ($source as $key => $value) {
            if ('id' === $key || \in_array($key, $plan['collections'], true) || isset($plan['inverseOneToOnes'][$key])) {
                continue;
            }

            // A relation source field (e.g. 'agendaId' → 'agenda')
            if (isset($plan['relations'][$key])) {
                if (null !== $value) {
                    $relation = $plan['relations'][$key];
                    $reference = $this->em->getReference($relation['targetEntity'], Ulid::fromString($value));
                    $plan['properties'][$relation['property']]['property']->setValue($entity, $reference);
                }
                continue;
            }

            // Try to set scalar property
            if (isset($plan['properties'][$key])) {
                $plan['properties'][$key]['property']->setValue($entity, $this->castValue($value, $plan['properties'][$key]['type']));
            }
        }

        return $entity;
    }

    /**
     * @param class-string $entityClass
     *
     * @return array{
     *     class: \ReflectionClass<object>,
     *     properties: array<string, array{property: \ReflectionProperty, type: ?string}>,
     *     collections: list<string>,
     *     inverseOneToOnes: array<string, string>,
     *     relations: array<string, array{property: string, targetEntity: class-string}>,
     * }
     */
    private function plan(string $entityClass): array
    {
        $class = new \ReflectionClass($entityClass);
        $properties = [];
        $collections = [];
        $inverseOneToOnes = [];
        $relations = [];

        // The class first, then its parents: a property redeclared below wins, as it does for the engine.
        for ($current = $class; false !== $current; $current = $current->getParentClass()) {
            foreach ($current->getProperties() as $property) {
                $name = $property->getName();
                $isCollection = [] !== $property->getAttributes(OneToMany::class) || [] !== $property->getAttributes(ManyToMany::class);

                if ($isCollection) {
                    $collections[] = $name;
                }

                foreach ($property->getAttributes(OneToOne::class) as $attribute) {
                    $mappedBy = $attribute->newInstance()->mappedBy;
                    if (null !== $mappedBy) {
                        $inverseOneToOnes[$name] = $mappedBy;
                    }
                }

                foreach ($property->getAttributes(IndexedRelation::class) as $attribute) {
                    $relation = $attribute->newInstance();
                    $relations[$relation->sourceField] ??= ['property' => $name, 'targetEntity' => $relation->targetEntity];
                }

                if (!isset($properties[$name])) {
                    $type = $property->getType();
                    $properties[$name] = [
                        'property' => $property,
                        'type' => $type instanceof \ReflectionNamedType ? $type->getName() : null,
                    ];
                }
            }
        }

        return ['class' => $class, 'properties' => $properties, 'collections' => $collections, 'inverseOneToOnes' => $inverseOneToOnes, 'relations' => $relations];
    }

    /**
     * The row owning a one-to-one with the rebuilt entity (an investment of a project), read from the
     * database; its own side points back at the rebuilt entity, not at the managed twin, as for the lines
     * of a collection.
     *
     * @param class-string $entityClass
     */
    private function inverseOneToOne(object $entity, string $entityClass, string $property, string $owningSide, Ulid $id): ?object
    {
        $targetClass = $this->em->getClassMetadata($entityClass)->getAssociationTargetClass($property);

        $row = $this->em->createQuery(\sprintf('SELECT target FROM %1$s target WHERE target.%2$s = :id', $targetClass, $owningSide))
            ->setParameter('id', $id, 'ulid')
            ->getOneOrNullResult();
        if (null === $row) {
            return null;
        }

        $this->em->detach($row);
        $this->em->getClassMetadata($targetClass)->reflFields[$owningSide]?->setValue($row, $entity);

        return $row;
    }

    private function castValue(mixed $value, ?string $typeName): mixed
    {
        if (null === $typeName || null === $value) {
            return $value;
        }

        // Handle DateTimeImmutable
        if (\DateTimeImmutable::class === $typeName && \is_string($value)) {
            return new \DateTimeImmutable($value);
        }

        // Handle enums
        if (is_subclass_of($typeName, \BackedEnum::class) && \is_string($value)) {
            return $typeName::from($value);
        }
        if (is_subclass_of($typeName, \BackedEnum::class) && \is_int($value)) {
            return $typeName::from($value);
        }

        // Handle Ulid
        if (Ulid::class === $typeName && \is_string($value)) {
            return Ulid::fromString($value);
        }

        // Handle an embedded value object, indexed as one object field
        if (\is_array($value) && is_subclass_of($typeName, SearchDocumentValue::class)) {
            return $typeName::fromSearchDocument($value);
        }

        return $value;
    }
}
