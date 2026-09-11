<?php

namespace Maggie\Calendar\Enum;

enum TaskCriticality: string
{
    case Low = 'low';
    case Medium = 'medium';
    case High = 'high';
    case Critical = 'critical';
}
