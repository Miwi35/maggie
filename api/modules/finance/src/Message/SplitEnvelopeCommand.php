<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class SplitEnvelopeCommand
{
    /** @param array<string, int> $amountsByEnvelopeId the new amount of each daughter to change, by envelope id */
    public function __construct(
        public string $userId,
        public string $envelopeId,
        public array $amountsByEnvelopeId,
    ) {
    }
}
