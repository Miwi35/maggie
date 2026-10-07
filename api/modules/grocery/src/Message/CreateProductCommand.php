<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class CreateProductCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public string $category,
        public ?string $defaultUnit = null,
        public ?string $preferredStoreId = null,
        public ?string $fallbackStoreId = null,
        public ?int $shelfLifeDays = null,
        public ?string $packagingUnit = null,
        public ?float $packagingSize = null,
        public ?string $packagingSizeUnit = null,
    ) {
    }
}
