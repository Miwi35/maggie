<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateAccountCommand
{
    use ClearsFieldsTrait;

    /** @param list<'bank'|'externalAccountId'> $clearFields */
    public function __construct(
        public string $userId,
        public string $accountId,
        public ?string $name = null,
        public ?string $type = null,
        public ?string $bank = null,
        public ?string $currency = null,
        public ?int $balanceCents = null,
        public ?bool $isCushion = null,
        public ?string $externalAccountId = null,
        public array $clearFields = [],
    ) {
    }
}
