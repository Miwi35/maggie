<?php

declare(strict_types=1);

namespace Maggie\Grocery\Enum;

enum ProductStockState: string
{
    case InStock = 'in_stock';
    case Low = 'low';
    case Out = 'out';
}
