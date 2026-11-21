<?php

declare(strict_types=1);

namespace Maggie\Calendar\Event;

/** A proaction completed with an interruption (MAG-886): the owner must be told. */
final readonly class ProactionInterrupted
{
    public function __construct(
        public string $proactionId,
    ) {
    }
}
