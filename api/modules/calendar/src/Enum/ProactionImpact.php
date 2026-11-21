<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/** How much what a check found matters to the owner, in Maggie's verdict (MAG-886). */
enum ProactionImpact: string
{
    case Low = 'low';
    case Medium = 'medium';
    case High = 'high';
}
