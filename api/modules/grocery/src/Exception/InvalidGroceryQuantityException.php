<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/**
 * A quantity that cannot be put on a line: `packagings` together with
 * `quantity`/`unit`, or `packagings` for a product without packaging. The API
 * answers it with a 400.
 */
final class InvalidGroceryQuantityException extends \DomainException
{
    public static function exclusive(): self
    {
        return new self('Send either quantity (with unit) or packagings, not both.');
    }

    public static function noPackaging(): self
    {
        return new self('packagings needs a product bought in a packaging: this one has none, send quantity instead.');
    }
}
