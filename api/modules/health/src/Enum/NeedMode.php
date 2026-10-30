<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Whether the day's need is computed from the profile or typed by the user (MAG-707). */
enum NeedMode: string
{
    case Computed = 'computed';
    case Entered = 'entered';
}
