<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class DeleteRecipeCommand
{
    public function __construct(
        public string $recipeId,
    ) {
    }
}
