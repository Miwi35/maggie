<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/** How a completed run reached the owner (MAG-886): an interruption, a message in the thread, or nothing. */
enum ProactionDelivery: string
{
    case Interrupt = 'interrupt';
    case Thread = 'thread';
    case None = 'none';

    /** The deliveries that told the owner something: a topic delivered so is not told again. */
    public function signaled(): bool
    {
        return self::None !== $this;
    }
}
