<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

/** The minutes of the two make-ups of tonight (MAG-771): the same session softer, a shorter one. */
final readonly class MakeupVariants
{
    public function __construct(
        public int $softerMinutes,
        public int $shorterMinutes,
    ) {
    }
}
