<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateMealCommand
{
    /**
     * @param string[]|null $recipeIds
     */
    public function __construct(
        public string $mealId,
        public ?string $date = null,
        public ?string $slot = null,
        public ?array $recipeIds = null,
    ) {
    }
}
