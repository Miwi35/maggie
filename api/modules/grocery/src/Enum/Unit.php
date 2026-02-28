<?php

declare(strict_types=1);

namespace Maggie\Grocery\Enum;

enum Unit: string
{
    case Gram = 'g';
    case Kilogram = 'kg';
    case Milliliter = 'ml';
    case Liter = 'l';
    case Centiliter = 'cl';
    case Piece = 'piece';
    case Bunch = 'bunch';
    case Can = 'can';
    case Bottle = 'bottle';
    case Pack = 'pack';
    case Sachet = 'sachet';
}
