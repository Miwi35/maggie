<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class LinkAccountToBankCommand
{
    public function __construct(
        public string $userId,
        public string $accountId,
        public string $bankAccountId,
        public bool $confirm = false,
    ) {
    }
}
