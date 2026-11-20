<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/** How firmly what a check found rests on a fact Maggie read, in her verdict (MAG-886). */
enum ProactionEvidence: string
{
    case Weak = 'weak';
    case Solid = 'solid';
}
