<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateAccountCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public string $type = 'checking',
        public ?string $bank = null,
        public string $currency = 'EUR',
        public int $balanceCents = 0,
        public bool $isCushion = false,
        public ?string $bridgeAccountId = null,
    ) {
    }
}
