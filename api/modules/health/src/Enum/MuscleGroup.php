<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** A muscle group an exercise works (MAG-774). */
enum MuscleGroup: string
{
    case Lats = 'lats';
    case UpperBack = 'upper_back';
    case Chest = 'chest';
    case Shoulders = 'shoulders';
    case Triceps = 'triceps';
    case Biceps = 'biceps';
    case Forearms = 'forearms';
    case Quads = 'quads';
    case Hamstrings = 'hamstrings';
    case Glutes = 'glutes';
    case Calves = 'calves';
    case Abs = 'abs';
    case Obliques = 'obliques';
    case LowerBack = 'lower_back';
}
