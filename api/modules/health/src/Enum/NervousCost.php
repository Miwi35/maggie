<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** How taxing an exercise is on the nervous system (MAG-774). */
enum NervousCost: string
{
    case Low = 'low';
    case Medium = 'medium';
    case High = 'high';
}
