<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Enum;

/** Where a cooking timer stands (MAG-582). */
enum CookingTimerStatus: string
{
    case Running = 'running';
    /** Stopped with its remaining time kept, until resumed. */
    case Paused = 'paused';
    /** Its end came and was notified, nobody has acknowledged it yet. */
    case Done = 'done';
    /** Its end was seen on a device (« OK »). */
    case Dismissed = 'dismissed';
    /** Stopped for good before its end, or left with the activity. */
    case Cancelled = 'cancelled';

    /** Still on the cook's screens: it runs, waits, or rings. */
    public function isActive(): bool
    {
        return self::Running === $this || self::Paused === $this || self::Done === $this;
    }
}
