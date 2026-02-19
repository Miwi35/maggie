<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

enum GroceryItemSource: string
{
    case Recipe = 'recipe';
    case Recurring = 'recurring';
    case Manual = 'manual';
}
