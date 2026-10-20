<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A period plan refused before anything was written; `index` is the line to blame, null when it is the plan itself. */
class PeriodPlanRejected extends \DomainException
{
    public function __construct(string $message, public readonly ?int $index = null, ?\Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
    }
}
