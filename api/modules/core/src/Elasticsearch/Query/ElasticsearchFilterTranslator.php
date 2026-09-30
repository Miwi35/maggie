<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Query;

final class ElasticsearchFilterTranslator
{
    /**
     * Translates API Platform request filters to ES query clauses.
     *
     * @param array<string, mixed>               $filters Request query parameters
     * @param array<string, array<string, mixed>> $fields  The index mapping, as IndexMetadataReader returns it.
     *                                                     Only sorting needs it — see sortField().
     * @return array{must: array<int, array<string, mixed>>, filter: array<int, array<string, mixed>>, sort: array<int, array<string, string>>}
     */
    public function translate(array $filters, array $fields = []): array
    {
        $must = [];
        $filter = [];
        $sort = [];

        foreach ($filters as $key => $value) {
            // PHP parses bracket query params into nested arrays:
            // exists[completedAt]=false → ['exists' => ['completedAt' => 'false']]
            // dueDate[before]=...      → ['dueDate' => ['before' => '...']]
            // order[startAt]=asc       → ['order' => ['startAt' => 'asc']]

            if ($key === 'exists' && \is_array($value)) {
                foreach ($value as $field => $flag) {
                    $filter[] = $this->buildExistsFilter($field, $flag);
                }
                continue;
            }

            if ($key === 'order' && \is_array($value)) {
                foreach ($value as $field => $direction) {
                    $sort[] = [self::sortField($field, $fields) => strtolower($direction)];
                }
                continue;
            }

            // Skip pagination params
            if (\in_array($key, ['page', 'itemsPerPage', '_page', '_per_page'], true)) {
                continue;
            }

            // Nested array on a field name → date filter operators
            if (\is_array($value)) {
                foreach ($value as $operator => $operand) {
                    if (\in_array($operator, ['after', 'before', 'strictly_after', 'strictly_before'], true)) {
                        $filter[] = $this->buildDateFilter($key, $operator, $operand);
                    }
                }
                continue;
            }

            // Search filter (exact)
            if (\is_string($value) && $value !== '') {
                $filter[] = ['term' => [$key => $value]];
            }
        }

        return [
            'must' => $must,
            'filter' => $filter,
            'sort' => $sort,
        ];
    }

    /**
     * Elasticsearch refuses to sort on an analysed `text` field: the values it
     * holds are the tokens, not the string. Where the mapping declares a
     * `keyword` sub-field, that is the sortable form of the same value.
     *
     * Without this, `order[name]=asc` made the search throw,
     * ElasticsearchCollectionProvider swallowed the exception and fell back to
     * Doctrine. The list came back sorted, so nothing looked wrong — the
     * collection was simply served by the other implementation, one extra
     * query and one warning line at a time. Silence of that kind is what this
     * ticket is about.
     *
     * A `text` field with no keyword sub-field is left alone, which still
     * throws. That is deliberate: the fix belongs on the entity
     * (`#[IndexedField(type: 'text', keyword: true)]`), and
     * QueryParameterContractTest fails on it before anyone can ship it.
     *
     * @param array<string, array<string, mixed>> $fields
     */
    private static function sortField(string $field, array $fields): string
    {
        $mapping = $fields[$field] ?? null;

        if (\is_array($mapping)
            && ($mapping['type'] ?? null) === 'text'
            && isset($mapping['fields']['keyword'])
        ) {
            return $field . '.keyword';
        }

        return $field;
    }

    /**
     * @return array<string, mixed>
     */
    private function buildDateFilter(string $field, string $operator, string $value): array
    {
        $esOp = match ($operator) {
            'after' => 'gte',
            'before' => 'lte',
            'strictly_after' => 'gt',
            'strictly_before' => 'lt',
            default => 'gte',
        };

        return ['range' => [$field => [$esOp => $value]]];
    }

    /**
     * @return array<string, mixed>
     */
    private function buildExistsFilter(string $field, mixed $value): array
    {
        $exists = \in_array($value, ['true', '1', true, 1], true);

        if ($exists) {
            return ['exists' => ['field' => $field]];
        }

        return ['bool' => ['must_not' => ['exists' => ['field' => $field]]]];
    }
}
