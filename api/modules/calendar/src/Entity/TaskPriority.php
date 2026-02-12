<?php

namespace Maggie\Calendar\Entity;

enum TaskPriority: string
{
    case Low = 'low';
    case Medium = 'medium';
    case High = 'high';
}
