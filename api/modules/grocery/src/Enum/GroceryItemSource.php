<?php

declare(strict_types=1);

namespace Maggie\Grocery\Enum;

enum GroceryItemSource: string
{
    case Recipe = 'recipe';
    case Recurring = 'recurring';
    case Manual = 'manual';
}
