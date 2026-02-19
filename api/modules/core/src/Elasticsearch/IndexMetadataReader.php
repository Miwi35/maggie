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
        $indexedAttrs = $ref->getAttributes(Indexed::class);

        if ($indexedAttrs === []) {
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
                $mapping = ['type' => $field->type];

                // boost is query-time only — not included in ES mapping
                if ($field->boost !== null) {
                    $boosts[$fieldName] = $field->boost;
                }
                if ($field->analyzer !== null) {
                    $mapping['analyzer'] = $field->analyzer;
                }
                if ($field->keyword && $field->type === 'text') {
                    $mapping['fields'] = ['keyword' => ['type' => 'keyword', 'ignore_above' => 256]];
                }
                if ($field->properties !== []) {
                    $mapping['properties'] = $field->properties;
                }

                $fields[$fieldName] = $mapping;
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
        while ($parent !== false) {
            foreach ($parent->getProperties() as $prop) {
                foreach ($prop->getAttributes(IndexedField::class) as $fieldAttr) {
                    $field = $fieldAttr->newInstance();
                    $fieldName = $field->name ?? $prop->getName();
                    if (!isset($fields[$fieldName])) {
                        $mapping = ['type' => $field->type];
                        if ($field->boost !== null) {
                            $boosts[$fieldName] = $field->boost;
                        }
                        if ($field->analyzer !== null) {
                            $mapping['analyzer'] = $field->analyzer;
                        }
                        if ($field->keyword && $field->type === 'text') {
                            $mapping['fields'] = ['keyword' => ['type' => 'keyword', 'ignore_above' => 256]];
                        }
                        if ($field->properties !== []) {
                            $mapping['properties'] = $field->properties;
                        }
                        $fields[$fieldName] = $mapping;
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
     * @return array<string, array<string, mixed>>
     */
    public function getMapping(string $className): array
    {
        $meta = $this->read($className);

        return $meta !== null ? $meta['fields'] : [];
    }

    public function getIndexName(string $className): ?string
    {
        $meta = $this->read($className);

        return $meta !== null ? $meta['index'] : null;
    }
}
