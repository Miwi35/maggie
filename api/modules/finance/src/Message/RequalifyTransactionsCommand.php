<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;

final readonly class RequalifyTransactionsCommand
{
    /** @param class-string<RecurringOperation|CategorizationRule> $operationClass */
    public function __construct(
        public string $operationClass,
        public string $operationId,
    ) {
    }
}
