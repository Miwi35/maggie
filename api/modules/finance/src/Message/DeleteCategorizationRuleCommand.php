<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DeleteCategorizationRuleCommand
{
    public function __construct(
        public string $userId,
        public string $categorizationRuleId,
    ) {
    }
}
