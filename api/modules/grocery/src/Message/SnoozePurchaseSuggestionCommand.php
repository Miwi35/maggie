<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

/** « Pas besoin » on a « tu en prends souvent » suggestion (MAG-495). */
final readonly class SnoozePurchaseSuggestionCommand
{
    public function __construct(
        public string $userId,
        public string $productId,
    ) {
    }
}
