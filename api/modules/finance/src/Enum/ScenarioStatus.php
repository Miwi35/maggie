<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** A draft is a proposal nobody kept yet; saved is the user's own. */
enum ScenarioStatus: string
{
    case Draft = 'draft';
    case Saved = 'saved';
}
