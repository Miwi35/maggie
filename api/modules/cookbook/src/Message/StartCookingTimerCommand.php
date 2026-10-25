<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/** Starts a timer of the Cuisine activity for a step of a recipe (MAG-582). */
final readonly class StartCookingTimerCommand
{
    public function __construct(
        public string $userId,
        public string $label,
        public string $recipeId,
        public int $stepPosition,
        public int $durationSeconds,
    ) {
    }
}
