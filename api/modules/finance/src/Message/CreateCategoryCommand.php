<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateCategoryCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public string $obligation = 'optional',
        public bool $passiveIncome = false,
        public ?string $parentId = null,
        public ?string $color = null,
        public ?string $icon = null,
    ) {
    }
}
