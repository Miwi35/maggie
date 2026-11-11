<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

/** A day's eating window (MAG-770), local times; the end may fall the next day. */
final readonly class FastingWindow
{
    public function __construct(
        public \DateTimeImmutable $start,
        public \DateTimeImmutable $end,
    ) {
    }
}
