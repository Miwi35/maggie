<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateCategoryCommand
{
    use ClearsFieldsTrait;

    /** @param list<'parentId'|'color'|'icon'> $clearFields */
    public function __construct(
        public string $userId,
        public string $categoryId,
        public ?string $name = null,
        public ?string $obligation = null,
        public ?bool $passiveIncome = null,
        public ?string $parentId = null,
        public ?string $color = null,
        public ?string $icon = null,
        public array $clearFields = [],
    ) {
    }
}
