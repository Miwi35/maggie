<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/**
 * The ingredients of a meal its owner chose to buy (MAG-295).
 *
 * `chosen` maps an ingredient id to a quantity in packagings to force, or to
 * null for what the recipes ask, rounded up. An empty map is a choice too:
 * nothing to buy, everything is in the cupboards.
 */
final readonly class ChooseMealGroceriesCommand
{
    /**
     * @param array<string, float|null> $chosen
     */
    public function __construct(
        public string $mealId,
        public string $userId,
        public array $chosen,
    ) {
    }
}
