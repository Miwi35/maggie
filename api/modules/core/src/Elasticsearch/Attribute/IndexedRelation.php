<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Attribute;

#[\Attribute(\Attribute::TARGET_PROPERTY)]
final class IndexedRelation
{
    public function __construct(
        public readonly string $targetEntity,
        public readonly string $sourceField,
    ) {}
}
