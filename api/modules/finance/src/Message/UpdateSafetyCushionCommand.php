<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class UpdateSafetyCushionCommand
{
    public function __construct(
        public string $userId,
        public string $safetyCushionId,
        public ?int $targetMonths = null,
        public ?int $monthlyNetIncomeCents = null,
        public ?int $rechargeCapCents = null,
        public ?int $rechargeTargetMonths = null,
    ) {
    }
}
