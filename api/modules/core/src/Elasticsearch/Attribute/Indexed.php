<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Attribute;

#[\Attribute(\Attribute::TARGET_CLASS)]
final class Indexed
{
    public function __construct(
        public readonly string $index,
        public readonly ?string $module = null,
    ) {}
}
