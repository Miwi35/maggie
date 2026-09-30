<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Attribute;

#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_METHOD)]
final class IndexedField
{
    /**
     * @param array<string, mixed> $properties For nested/object types
     */
    public function __construct(
        public readonly string $type = 'text',
        public readonly ?string $name = null,
        public readonly ?float $boost = null,
        public readonly ?string $analyzer = null,
        public readonly bool $keyword = false,
        public readonly array $properties = [],
    ) {
    }
}
