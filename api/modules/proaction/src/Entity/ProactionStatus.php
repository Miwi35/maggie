<?php

declare(strict_types=1);

namespace Maggie\Proaction\Entity;

enum ProactionStatus: string
{
    case Pending = 'pending';
    case Running = 'running';
    case Success = 'success';
    case Failed = 'failed';
}
