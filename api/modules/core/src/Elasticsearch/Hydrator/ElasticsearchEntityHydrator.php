<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Hydrator;

use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Mapping\ManyToMany;
use Doctrine\ORM\Mapping\OneToMany;
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
     * @param array<string, mixed> $source      ES _source document
     * @param class-string         $entityClass
     */
    public function hydrate(array $source, string $entityClass): object
    {
        /** @var \ReflectionClass<object> $ref */
        $ref = new \ReflectionClass($entityClass);
        $entity = $ref->newInstanceWithoutConstructor();

        // Set the ID
        if (isset($source['id'])) {
            $this->setProperty($ref, $entity, 'id', Ulid::fromString($source['id']));
        }

        // The to-many sides are not in the document (or only as a summary of
        // the lines): they are read from the database when something looks.
        $collections = $this->getCollectionProperties($ref);
        if (isset($source['id'])) {
            foreach ($collections as $propName) {
                $this->setProperty($ref, $entity, $propName, new LazyAssociationCollection($this->em, $entity, Ulid::fromString($source['id']), $propName));
            }
        }

        // Collect relation metadata
        $relations = $this->getRelationMap($ref);

        // Set all other fields from source
        foreach ($source as $key => $value) {
            if ('id' === $key || \in_array($key, $collections, true)) {
                continue;
            }

            // Check if this is a relation source field (e.g. 'agendaId' → 'agenda')
            $relProp = $this->findRelationBySourceField($relations, $key);
            if (null !== $relProp) {
                [$propName, $relMeta] = $relProp;
                if (null !== $value) {
                    /** @var class-string $targetEntity */
                    $targetEntity = $relMeta['targetEntity'];
                    $reference = $this->em->getReference($targetEntity, Ulid::fromString($value));
                    $this->setProperty($ref, $entity, $propName, $reference);
                }
                continue;
            }

            // Try to set scalar property
            if ($this->hasProperty($ref, $key)) {
                $propRef = $this->getPropertyRef($ref, $key);
                $typedValue = $this->castValue($value, $propRef);
                $this->setProperty($ref, $entity, $key, $typedValue);
            }
        }

        return $entity;
    }

    /**
     * @param \ReflectionClass<object> $ref
     *
     * @return list<string>
     */
    private function getCollectionProperties(\ReflectionClass $ref): array
    {
        $names = [];
        $current = $ref;

        do {
            foreach ($current->getProperties() as $prop) {
                if ([] !== $prop->getAttributes(OneToMany::class) || [] !== $prop->getAttributes(ManyToMany::class)) {
                    $names[] = $prop->getName();
                }
            }
            $current = $current->getParentClass();
        } while (false !== $current);

        return $names;
    }

    /**
     * @param \ReflectionClass<object> $ref
     *
     * @return array<string, array{targetEntity: string, sourceField: string}>
     */
    private function getRelationMap(\ReflectionClass $ref): array
    {
        $relations = [];
        $current = $ref;

        do {
            foreach ($current->getProperties() as $prop) {
                foreach ($prop->getAttributes(IndexedRelation::class) as $attr) {
                    $rel = $attr->newInstance();
                    $relations[$prop->getName()] = [
                        'targetEntity' => $rel->targetEntity,
                        'sourceField' => $rel->sourceField,
                    ];
                }
            }
            $current = $current->getParentClass();
        } while (false !== $current);

        return $relations;
    }

    /**
     * @param array<string, array{targetEntity: string, sourceField: string}> $relations
     *
     * @return array{string, array{targetEntity: string, sourceField: string}}|null
     */
    private function findRelationBySourceField(array $relations, string $sourceField): ?array
    {
        foreach ($relations as $propName => $relMeta) {
            if ($relMeta['sourceField'] === $sourceField) {
                return [$propName, $relMeta];
            }
        }

        return null;
    }

    /** @param \ReflectionClass<object> $ref */
    private function hasProperty(\ReflectionClass $ref, string $name): bool
    {
        $current = $ref;
        do {
            if ($current->hasProperty($name)) {
                return true;
            }
            $current = $current->getParentClass();
        } while (false !== $current);

        return false;
    }

    /** @param \ReflectionClass<object> $ref */
    private function getPropertyRef(\ReflectionClass $ref, string $name): \ReflectionProperty
    {
        $current = $ref;
        do {
            if ($current->hasProperty($name)) {
                return $current->getProperty($name);
            }
            $current = $current->getParentClass();
        } while (false !== $current);

        throw new \RuntimeException("Property {$name} not found on {$ref->getName()}");
    }

    /** @param \ReflectionClass<object> $ref */
    private function setProperty(\ReflectionClass $ref, object $entity, string $name, mixed $value): void
    {
        $current = $ref;
        do {
            if ($current->hasProperty($name)) {
                $prop = $current->getProperty($name);
                $prop->setValue($entity, $value);

                return;
            }
            $current = $current->getParentClass();
        } while (false !== $current);
    }

    private function castValue(mixed $value, \ReflectionProperty $prop): mixed
    {
        $type = $prop->getType();

        if (null === $type || null === $value) {
            return $value;
        }

        $typeName = $type instanceof \ReflectionNamedType ? $type->getName() : null;

        if (null === $typeName) {
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
