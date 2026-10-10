<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Query;

use Maggie\Core\Identifier\ResourceIdentifier;
use Maggie\Core\Time\DayBound;

final class ElasticsearchFilterTranslator
{
    /**
     * Translates API Platform request filters to ES query clauses.
     *
     * @param array<string, mixed>                                            $filters   Request query parameters
     * @param array<string, array<string, mixed>>                             $fields    The index mapping, as
     *                                                                                   IndexMetadataReader returns it.
     *                                                                                   Sorting needs it — see sortField().
     * @param array<string, array{targetEntity: string, sourceField: string}> $relations The indexed relations, same
     *                                                                                   source. Filtering on one needs
     *                                                                                   it — see relationClause().
     * @param array<string, array{field: string, exclusiveEnd: bool}>         $dayFields an instant field → the day field a
     *                                                                                   document holds instead when it
     *                                                                                   has no instant — see dayAwareDateFilter()
     *
     * @return array{must: array<int, array<string, mixed>>, filter: array<int, array<string, mixed>>, sort: array<int, array<string, string>>}
     */
    public function translate(array $filters, array $fields = [], array $relations = [], array $dayFields = []): array
    {
        $must = [];
        $filter = [];
        $sort = [];

        foreach ($filters as $key => $value) {
            // PHP parses bracket query params into nested arrays:
            // exists[completedAt]=false → ['exists' => ['completedAt' => 'false']]
            // dueDate[before]=...      → ['dueDate' => ['before' => '...']]
            // order[startAt]=asc       → ['order' => ['startAt' => 'asc']]

            if ('exists' === $key && \is_array($value)) {
                foreach ($value as $field => $flag) {
                    $filter[] = $this->buildExistsFilter($field, $flag);
                }
                continue;
            }

            if ('order' === $key && \is_array($value)) {
                foreach ($value as $field => $direction) {
                    $sort[] = [self::sortField($field, $fields) => strtolower($direction)];
                }
                continue;
            }

            // Skip pagination params
            if (\in_array($key, ['page', 'itemsPerPage', '_page', '_per_page'], true)) {
                continue;
            }

            // A relation, before the generic branches below: it is the one
            // parameter whose field name on the wire is not the field name in
            // the index, and the one whose unusable values must not fall
            // through to "no clause". `?account[]=…` is an array and
            // `?account=` is empty; either would otherwise reach the end of
            // this loop, produce nothing, and answer a request for one
            // account with every account's rows.
            if (isset($relations[$key])) {
                $filter[] = self::relationClause($key, $value, $relations);
                continue;
            }

            // A list of values on a field name → any of them
            // (`transferKind[]=none&transferKind[]=internal`), the shape
            // SearchFilter accepts on the Doctrine side.
            if (\is_array($value) && [] !== $value && array_is_list($value) && [] === array_filter($value, static fn (mixed $v) => !\is_string($v) || '' === $v)) {
                $filter[] = ['terms' => [$key => $value]];
                continue;
            }

            // Nested array on a field name → date filter operators
            if (\is_array($value)) {
                foreach ($value as $operator => $operand) {
                    if (\in_array($operator, ['after', 'before', 'strictly_after', 'strictly_before'], true)) {
                        $filter[] = isset($dayFields[$key])
                            ? $this->dayAwareDateFilter($key, $dayFields[$key]['field'], $dayFields[$key]['exclusiveEnd'], $operator, (string) $operand)
                            : $this->buildDateFilter($key, $operator, $operand);
                    }
                }
                continue;
            }

            // Search filter (exact)
            if (\is_string($value) && '' !== $value) {
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
     * The clause for a relation, resolving both halves of the mismatch the
     * clients cannot see.
     *
     * The name: API Platform calls the filter after the property —
     * `account` — while IndexManager flattens the relation to its
     * `sourceField`, `accountId`. A term query on `account` names a field
     * the mapping does not declare and matches nothing.
     *
     * The value: the clients send the IRI the provider handed them; the
     * index holds the bare identifier.
     *
     * A value naming no resource gets a clause that matches nothing, never
     * no clause at all. This is the same rule UlidRelationFilter applies on
     * the Doctrine side, and it has to hold here too — in production this is
     * the path that serves the collection, and Doctrine only takes over when
     * Elasticsearch throws.
     *
     * @param array<string, array{targetEntity: string, sourceField: string}> $relations
     *
     * @return array<string, mixed>
     */
    private static function relationClause(string $key, mixed $value, array $relations): array
    {
        $identifier = ResourceIdentifier::fromRequestValue($value);

        if (null === $identifier) {
            // An `ids` query with no value matches no document, whatever the
            // mapping holds.
            return ['ids' => ['values' => []]];
        }

        return ['term' => [$relations[$key]['sourceField'] => (string) $identifier]];
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
            return $field.'.keyword';
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
     * A bound on an instant that also holds, by its day, for a document with
     * no instant — an all-day event, which has `startDate` and no `startAt`
     * (MAG-382). The clients keep asking `startAt[before]=…` and get both
     * kinds of event; the day of the bound is {@see DayBound}'s, the one
     * the Doctrine filter and the repository use too.
     *
     * @return array<string, mixed>
     */
    private function dayAwareDateFilter(string $field, string $dayField, bool $exclusiveEnd, string $operator, string $value): array
    {
        $bound = DayBound::forOperator($operator, $value, $exclusiveEnd);
        if (null === $bound) {
            return $this->buildDateFilter($field, $operator, $value);
        }

        return ['bool' => [
            'should' => [
                $this->buildDateFilter($field, $operator, $value),
                ['bool' => [
                    'must_not' => [['exists' => ['field' => $field]]],
                    'filter' => [['range' => [$dayField => [$bound[0] => $bound[1]->format('Y-m-d')]]]],
                ]],
            ],
            'minimum_should_match' => 1,
        ]];
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
