<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateMealCommand
{
    /**
     * @param string[] $recipeIds
     */
    public function __construct(
        public string $date,
        public string $slot,
        public array $recipeIds = [],
        public ?string $agendaId = null,
        public ?string $userId = null,
    ) {
    }
}
