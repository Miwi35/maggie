<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What a project's date means (MAG-519).
 *
 * `none`: no date, the planning places it. `fixed`: the date is locked
 * (a wedding, a move). `deadline`: done at the latest on that date.
 */
enum ProjectDateMode: string
{
    case None = 'none';
    case Fixed = 'fixed';
    case Deadline = 'deadline';

    public function needsDate(): bool
    {
        return self::None !== $this;
    }
}
