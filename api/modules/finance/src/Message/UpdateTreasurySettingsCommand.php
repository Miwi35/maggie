<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** `salaryExpectedDay` is always written: null forgets the day. */
final readonly class UpdateTreasurySettingsCommand
{
    public function __construct(
        public string $userId,
        public string $treasurySettingsId,
        public ?int $salaryExpectedDay,
    ) {
    }
}
