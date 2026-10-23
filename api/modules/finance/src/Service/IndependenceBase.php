<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/** The two measured figures the date N hangs on: the rentes already there and the train de vie. */
final readonly class IndependenceBase
{
    public function __construct(
        public int $passiveIncomeCents,
        public int $lifestyleCents,
    ) {
    }
}
