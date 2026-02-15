<?php

declare(strict_types=1);

namespace Maggie\Memory\Entity;

enum MemoryType: string
{
    case Factual = 'factual';
    case Episodic = 'episodic';
}
