<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Doctrine\ORM\QueryBuilder;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;

final class IndexMetadataReader
{
    /**
     * @return array{index: string, module: ?string, globalSearch: bool, fields: array<string, array<string, mixed>>, relations: array<string, array{targetEntity: string, sourceField: string}>, boosts: array<string, float>, dayFields: array<string, array{field: string, exclusiveEnd: bool}>}|null
     */
    public function read(string $className): ?array
    {
        $ref = new \ReflectionClass($className);
        $indexedAttrs = $ref->getAttributes(Indexed::class);

        if ([] === $indexedAttrs) {
            return null;
        }

        $indexed = $indexedAttrs[0]->newInstance();
        $fields = [];
        $relations = [];
        $boosts = [];
        $dayFields = [];

        foreach ($ref->getProperties() as $prop) {
            foreach ($prop->getAttributes(IndexedField::class) as $fieldAttr) {
                $field = $fieldAttr->newInstance();
                $fieldName = $field->name ?? $prop->getName();

                // boost is query-time only — not included in ES mapping
                if (null !== $field->boost) {
                    $boosts[$fieldName] = $field->boost;
                }

                $fields[$fieldName] = self::mappingOf($field);
                if (null !== $field->dayField) {
                    $dayFields[$fieldName] = ['field' => $field->dayField, 'exclusiveEnd' => $field->dayFieldIsExclusiveEnd];
                }
            }

            foreach ($prop->getAttributes(IndexedRelation::class) as $relAttr) {
                $rel = $relAttr->newInstance();
                $relations[$prop->getName()] = [
                    'targetEntity' => $rel->targetEntity,
                    'sourceField' => $rel->sourceField,
                ];
            }
        }

        // Also check parent class properties (for inheritance like Meal extends Event)
        $parent = $ref->getParentClass();
        while (false !== $parent) {
            foreach ($parent->getProperties() as $prop) {
                foreach ($prop->getAttributes(IndexedField::class) as $fieldAttr) {
                    $field = $fieldAttr->newInstance();
                    $fieldName = $field->name ?? $prop->getName();
                    if (!isset($fields[$fieldName])) {
                        if (null !== $field->boost) {
                            $boosts[$fieldName] = $field->boost;
                        }
                        $fields[$fieldName] = self::mappingOf($field);
                        if (null !== $field->dayField) {
                            $dayFields[$fieldName] = ['field' => $field->dayField, 'exclusiveEnd' => $field->dayFieldIsExclusiveEnd];
                        }
                    }
                }

                foreach ($prop->getAttributes(IndexedRelation::class) as $relAttr) {
                    $rel = $relAttr->newInstance();
                    if (!isset($relations[$prop->getName()])) {
                        $relations[$prop->getName()] = [
                            'targetEntity' => $rel->targetEntity,
                            'sourceField' => $rel->sourceField,
                        ];
                    }
                }
            }
            $parent = $parent->getParentClass();
        }

        return [
            'index' => $indexed->index,
            'module' => $indexed->module,
            'globalSearch' => $indexed->globalSearch,
            'fields' => $fields,
            'relations' => $relations,
            'boosts' => $boosts,
            'dayFields' => $dayFields,
        ];
    }

    /**
     * The index mapping of one declared field. `boost` is left out on purpose:
     * it is a query-time weight, and Elasticsearch rejects it in a mapping.
     *
     * @return array<string, mixed>
     */
    private static function mappingOf(IndexedField $field): array
    {
        $mapping = ['type' => $field->type];

        if (null !== $field->analyzer) {
            $mapping['analyzer'] = $field->analyzer;
        }
        if (null !== $field->format) {
            $mapping['format'] = $field->format;
        }
        if ($field->keyword && 'text' === $field->type) {
            $mapping['fields'] = ['keyword' => ['type' => 'keyword', 'ignore_above' => 256]];
        }
        if ([] !== $field->properties) {
            $mapping['properties'] = $field->properties;
        }

        return $mapping;
    }

    /**
     * @return array<string, array<string, mixed>>
     */
    public function getMapping(string $className): array
    {
        $meta = $this->read($className);

        return null !== $meta ? $meta['fields'] : [];
    }

    /**
     * Every index a row of this class is served from: its own, then those of its
     * ancestors. A Meal is a document of `meals` and of `events`; an Ingredient,
     * which declares no index, is a document of `products`. A row that leaves the
     * database has to leave all of them.
     *
     * @return list<string>
     */
    public function indicesOf(string $className): array
    {
        $indices = [];
        for ($ref = new \ReflectionClass($className); false !== $ref; $ref = $ref->getParentClass()) {
            foreach ($ref->getAttributes(Indexed::class) as $attribute) {
                $indices[] = $attribute->newInstance()->index;
            }
        }

        return array_values(array_unique($indices));
    }

    /**
     * The one index a row of this class is written to: its own, else its nearest
     * ancestor's. A Meal is a row of `events` for the database, not a document of
     * it — it would be served to the agenda a second time, next to `meals`.
     */
    public function indexOf(string $className): ?string
    {
        return $this->indicesOf($className)[0] ?? null;
    }

    /**
     * Of these classes, those that declare an index of their own: their rows are
     * not documents of the index of the class they extend.
     *
     * @param list<class-string> $classes
     *
     * @return list<class-string>
     */
    public static function subclassesWithOwnIndex(array $classes): array
    {
        return array_values(array_filter(
            $classes,
            static fn (string $class) => [] !== (new \ReflectionClass($class))->getAttributes(Indexed::class),
        ));
    }

    /**
     * Narrows a query on an indexed class to the rows its index holds: no row of a
     * subclass that has an index of its own.
     */
    public static function restrictToOwnRows(QueryBuilder $queryBuilder, string $alias, string $className): void
    {
        $metadata = $queryBuilder->getEntityManager()->getClassMetadata($className); // @phpstan-ignore argument.templateType
        $subclasses = self::subclassesWithOwnIndex($metadata->subClasses);

        if ([] !== $subclasses) {
            $queryBuilder->andWhere(sprintf('%s NOT INSTANCE OF (%s)', $alias, implode(', ', $subclasses)));
        }
    }

    public function getIndexName(string $className): ?string
    {
        $meta = $this->read($className);

        return null !== $meta ? $meta['index'] : null;
    }
}
