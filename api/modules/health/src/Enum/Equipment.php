<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** The gear an exercise needs; none listed means nothing (MAG-774). */
enum Equipment: string
{
    case PullUpBar = 'pull_up_bar';
    case WeightedBag = 'weighted_bag';
    case Chair = 'chair';
    case Table = 'table';
    case Band = 'band';
    case ParallelBars = 'parallel_bars';
    case Wall = 'wall';
}
