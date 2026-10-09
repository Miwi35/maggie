<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class ApplyCategorizationRuleCommand
{
    public function __construct(
        public string $ruleId,
    ) {
    }
}
