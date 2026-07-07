<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class UpdateAccountCommand
{
    public function __construct(
        public string $accountId,
        public ?string $name = null,
        public ?string $type = null,
        public ?string $bank = null,
        public ?string $currency = null,
        public ?int $balanceCents = null,
        public ?bool $isCushion = null,
        public ?string $bridgeAccountId = null,
    ) {
    }
}
