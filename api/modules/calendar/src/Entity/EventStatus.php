<?php

namespace Maggie\Calendar\Entity;

enum EventStatus: string
{
    case Confirmed = 'confirmed';
    case Tentative = 'tentative';
    case Cancelled = 'cancelled';
}
