<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

enum GroceryListStatus: string
{
    case Draft = 'draft';
    case Active = 'active';
    case Completed = 'completed';
}
