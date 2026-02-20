<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Query;

final class ElasticsearchFilterTranslator
{
    /**
     * Translates API Platform request filters to ES query clauses.
     *
     * @param array<string, mixed> $filters Request query parameters
     * @return array{must: array<int, array<string, mixed>>, filter: array<int, array<string, mixed>>, sort: array<int, array<string, string>>}
     */
    public function translate(array $filters): array
    {
        $must = [];
        $filter = [];
        $sort = [];

        foreach ($filters as $key => $value) {
            // Date filters: startAt[after]=2026-01-01
            if (preg_match('/^(\w+)\[(after|before|strictly_after|strictly_before)]$/', $key, $m)) {
                $filter[] = $this->buildDateFilter($m[1], $m[2], $value);
                continue;
            }

            // Exists filter: exists[rrule]=true
            if (preg_match('/^exists\[(\w+)]$/', $key, $m)) {
                $filter[] = $this->buildExistsFilter($m[1], $value);
                continue;
            }

            // Ordering: order[startAt]=asc
            if (preg_match('/^order\[(\w+)]$/', $key, $m)) {
                $sort[] = [$m[1] => strtolower($value)];
                continue;
            }

            // Skip pagination params
            if (\in_array($key, ['page', 'itemsPerPage', '_page', '_per_page'], true)) {
                continue;
            }

            // Search filter (exact or partial)
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
