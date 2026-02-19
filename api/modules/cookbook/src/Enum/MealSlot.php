<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

enum MealSlot: string
{
    case Lunch = 'lunch';
    case Dinner = 'dinner';
}
