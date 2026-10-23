<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * A project's investment (MAG-557), whole: it replaces the current one. `data`
 * is the request as it came — `{priceCents?, downPaymentCents, loan?,
 * monthlyRentCents, resale?}` — ProjectInvestmentRules reads it, for the API
 * and the MCP tool alike.
 */
final readonly class SetProjectInvestmentCommand
{
    /** @param array<string, mixed> $data */
    public function __construct(
        public string $userId,
        public string $projectId,
        public array $data,
    ) {
    }
}
