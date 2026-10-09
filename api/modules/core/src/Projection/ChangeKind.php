<?php

declare(strict_types=1);

namespace Maggie\Core\Projection;

enum ChangeKind
{
    case Inserted;
    case Updated;
    case Deleted;
}
