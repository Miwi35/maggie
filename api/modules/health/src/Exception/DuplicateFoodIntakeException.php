<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** « Mangé » a second time on the same meal: a 400, never a second intake. */
final class DuplicateFoodIntakeException extends InvalidFoodIntakeException
{
    public function __construct()
    {
        parent::__construct('mealId', 'this meal is already eaten: it is in the journal once. Update or delete that intake instead.');
    }
}
