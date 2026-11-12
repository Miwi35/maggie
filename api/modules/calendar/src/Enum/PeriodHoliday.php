<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * Which public holidays a period stands for (MAG-809): set by the import of
 * the holidays only, null on a period someone made.
 */
enum PeriodHoliday: string
{
    case France = 'france';
    case Geneve = 'geneve';
    case Both = 'both';
}
