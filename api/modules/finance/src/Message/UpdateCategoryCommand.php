<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class UpdateCategoryCommand
{
    public function __construct(
        public string $categoryId,
        public ?string $name = null,
        public ?string $obligation = null,
        public ?string $parentId = null,
        public ?string $color = null,
        public ?string $icon = null,
    ) {
    }
}
