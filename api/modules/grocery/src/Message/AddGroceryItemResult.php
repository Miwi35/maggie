<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;

/**
 * What adding an item leaves: the list, the line, and what the planned meals
 * need of its product over the week (MAG-573) — the clients and Maggie warn
 * when the line does not cover it.
 *
 * @phpstan-import-type Need from \Maggie\Grocery\Service\Needs
 */
final readonly class AddGroceryItemResult
{
    /**
     * @param Need|null $recipeNeed        the need of the week's meals, in base units
     * @param int|null  $packagingsToCover the packagings that would cover it, when the line falls short
     */
    public function __construct(
        public GroceryList $list,
        public GroceryItem $item,
        public ?array $recipeNeed,
        public bool $coversWeek,
        public ?int $packagingsToCover,
    ) {
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'success' => true,
            'itemCount' => \count($this->list->getItems()->filter(static fn (GroceryItem $i) => !$i->isChecked())),
            'itemId' => (string) $this->item->getId(),
            'quantity' => $this->item->getQuantity(),
            'unit' => $this->item->getUnit()?->value,
            'desiredQuantity' => $this->item->getDesiredQuantity(),
            'desiredUnit' => $this->item->getDesiredUnit()?->value,
            'recipeNeed' => $this->recipeNeed,
            'coversWeek' => $this->coversWeek,
            'packagingsToCover' => $this->packagingsToCover,
        ];
    }
}
