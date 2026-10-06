<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class UpdateEnvelopeCommand
{
    public function __construct(
        public string $userId,
        public string $envelopeId,
        public ?string $categoryId = null,
        public ?int $amountCents = null,
        public ?int $year = null,
        public ?string $mode = null,
        public ?int $month = null,
        public ?string $currency = null,
    ) {
    }
}
