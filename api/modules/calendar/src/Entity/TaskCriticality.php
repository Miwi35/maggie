<?php

namespace Maggie\Calendar\Entity;

enum TaskCriticality: string
{
    case Low = 'low';
    case Medium = 'medium';
    case High = 'high';
    case Critical = 'critical';
}
