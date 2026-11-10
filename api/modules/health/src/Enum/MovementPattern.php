<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** The movement pattern an exercise trains (MAG-774). */
enum MovementPattern: string
{
    case VerticalPull = 'vertical_pull';
    case HorizontalPull = 'horizontal_pull';
    case VerticalPush = 'vertical_push';
    case HorizontalPush = 'horizontal_push';
    case Squat = 'squat';
    case Hinge = 'hinge';
    case Lunge = 'lunge';
    case CoreAnterior = 'core_anterior';
    case CoreLateral = 'core_lateral';
}
