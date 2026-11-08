<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Who last wrote a health journal day's mood, fatigue or note (MAG-750): the user, or Maggie (« avec Maggie · 21:42 »). */
enum HealthDayAuthor: string
{
    case User = 'user';
    case Maggie = 'maggie';
}
