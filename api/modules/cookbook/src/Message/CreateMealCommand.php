<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateMealCommand
{
    /**
     * @param string[] $recipeIds
     * @param int|null $guests    how many people it is cooked for; null follows the user's default
     */
    public function __construct(
        public string $date,
        public string $slot,
        public array $recipeIds = [],
        public ?string $userId = null,
        public ?int $guests = null,
        public ?string $customTitle = null,
    ) {
    }
}
