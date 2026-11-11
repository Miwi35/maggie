<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Compound (several joints) or isolation (MAG-774). */
enum ExerciseKind: string
{
    case Compound = 'compound';
    case Isolation = 'isolation';
}
