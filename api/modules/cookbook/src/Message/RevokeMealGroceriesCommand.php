<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class RevokeMealGroceriesCommand
{
    /** @param list<array{groceryItemId: string, quantity: float}> $contributions what the removed meal had put on the list */
    public function __construct(
        public string $mealId,
        public string $userId,
        public array $contributions,
    ) {
    }
}
