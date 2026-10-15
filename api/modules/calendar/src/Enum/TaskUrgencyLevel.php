<?php

namespace Maggie\Calendar\Enum;

/**
 * How urgent a task is (MAG-406): computed from its criticality and how close
 * or how far past its due day is, never entered. It is what the alerts follow.
 */
enum TaskUrgencyLevel: string
{
    case Low = 'low';
    case Normal = 'normal';
    case High = 'high';
    case Critical = 'critical';
}
