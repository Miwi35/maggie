<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateProductCommand
{
    use ClearsFieldsTrait;

    /**
     * @param list<'defaultUnit'|'preferredStore'|'fallbackStore'|'shelfLifeDays'|'packagingUnit'|'packagingSize'|'packagingSizeUnit'|'restockQuantity'> $clearFields
     * @param list<array{store: string, aisle: ?string}>|null                                                                                            $aisles      its aisle in every store it has one in (MAG-493): a store left out, or with a null
     *                                                                                                                                                                aisle, is « Sans rayon »; an aisle is an id or a name, a new name is created last; null leaves them as they are
     */
    public function __construct(
        public string $productId,
        public ?string $name = null,
        public ?string $category = null,
        public ?string $defaultUnit = null,
        public ?string $preferredStoreId = null,
        public ?string $fallbackStoreId = null,
        public ?int $shelfLifeDays = null,
        public ?string $packagingUnit = null,
        public ?float $packagingSize = null,
        public ?string $packagingSizeUnit = null,
        public ?string $stockState = null,
        public ?int $restockQuantity = null,
        public ?bool $autoRestock = null,
        public array $clearFields = [],
        public ?array $aisles = null,
    ) {
    }
}
