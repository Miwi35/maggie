<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

use Maggie\Grocery\Entity\GroceryItem;

/**
 * A quantity that cannot be put on a line: `packagings` together with
 * `quantity`/`unit`, `packagings` for a product without packaging, or a value
 * of zero or less. The API answers it with a 400 that recalls the line as it
 * stands, untouched (MAG-573).
 */
final class InvalidGroceryQuantityException extends \DomainException
{
    /** @var array<string, mixed>|null */
    public readonly ?array $item;

    private function __construct(string $message, ?GroceryItem $item)
    {
        parent::__construct($message);
        $this->item = $item?->quantityState();
    }

    public static function exclusive(?GroceryItem $item = null): self
    {
        return new self('Send either quantity (with unit) or packagings, not both.', $item);
    }

    public static function notPositive(?GroceryItem $item = null): self
    {
        return new self('quantity and packagings must be greater than zero.', $item);
    }

    public static function noPackaging(?GroceryItem $item = null): self
    {
        return new self('packagings needs a product bought in a packaging: this one has none, send quantity instead.', $item);
    }
}
