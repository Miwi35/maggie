<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;

final class IndexMetadataReader
{
    /**
     * @return array{index: string, module: ?string, fields: array<string, array<string, mixed>>, relations: array<string, array{targetEntity: string, sourceField: string}>, boosts: array<string, float>}|null
     */
    public function read(string $className): ?array
    {
        $ref = new \ReflectionClass($className);

        // Class attributes are not inherited: a subclass of an indexed entity
        // (Ingredient extends Product) shares its parent's index unless it declares its own.
        $indexedAttrs = [];
        for ($class = $ref; false !== $class && [] === $indexedAttrs; $class = $class->getParentClass()) {
            $indexedAttrs = $class->getAttributes(Indexed::class);
        }

        if ([] === $indexedAttrs) {
            return null;
        }

        $indexed = $indexedAttrs[0]->newInstance();
        $fields = [];
        $relations = [];
        $boosts = [];

        foreach ($ref->getProperties() as $prop) {
            foreach ($prop->getAttributes(IndexedField::class) as $fieldAttr) {
                $field = $fieldAttr->newInstance();
                $fieldName = $field->name ?? $prop->getName();

                // boost is query-time only — not included in ES mapping
                if (null !== $field->boost) {
                    $boosts[$fieldName] = $field->boost;
                }

                $fields[$fieldName] = self::mappingOf($field);
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
            'fields' => $fields,
            'relations' => $relations,
            'boosts' => $boosts,
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

    public function getIndexName(string $className): ?string
    {
        $meta = $this->read($className);

        return null !== $meta ? $meta['index'] : null;
    }
}
