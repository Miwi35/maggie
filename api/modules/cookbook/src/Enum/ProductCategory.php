<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

enum ProductCategory: string
{
    case Produce = 'produce';
    case Dairy = 'dairy';
    case Meat = 'meat';
    case Fish = 'fish';
    case Grain = 'grain';
    case Spice = 'spice';
    case Condiment = 'condiment';
    case Frozen = 'frozen';
    case Beverage = 'beverage';
    case Household = 'household';
    case Hygiene = 'hygiene';
    case Cleaning = 'cleaning';
    case Other = 'other';
}
