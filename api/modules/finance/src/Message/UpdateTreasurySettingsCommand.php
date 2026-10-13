<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * Only the fields the caller names are written: `salaryExpectedDay` when
 * `changesSalaryExpectedDay` (null forgets the day), `overdraftLimitCents`
 * when it is not null.
 */
final readonly class UpdateTreasurySettingsCommand
{
    public function __construct(
        public string $userId,
        public string $treasurySettingsId,
        public ?int $salaryExpectedDay = null,
        public bool $changesSalaryExpectedDay = false,
        public ?int $overdraftLimitCents = null,
    ) {
    }
}
