<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateMealCommand
{
    /**
     * @param string[]|null $recipeIds
     * @param int|null      $guests           the meal's own number of guests; null keeps it
     * @param bool          $useDefaultGuests gives the meal back to the user's default, whatever `$guests` says
     */
    public function __construct(
        public string $mealId,
        public ?string $date = null,
        public ?string $slot = null,
        public ?array $recipeIds = null,
        public ?int $guests = null,
        public bool $useDefaultGuests = false,
        /** Null leaves the title as it is; a blank one goes back to the recipes' names. */
        public ?string $customTitle = null,
    ) {
    }
}
