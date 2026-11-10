<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** The branch of a progression ladder (MAG-774). */
enum LadderBranch: string
{
    case Main = 'main';
    case WeightedBag = 'weighted_bag';
    case Dips = 'dips';
}
