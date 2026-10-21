<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * A project's whole list of funding sources (MAG-524): it replaces the current
 * one. Each source is `{kind, amountCents, account?, loan?}` as the request
 * sent it — ProjectFundingRules reads it, for the API and the MCP tool alike.
 */
final readonly class SetProjectFundingCommand
{
    /** @param list<mixed> $sources */
    public function __construct(
        public string $userId,
        public string $projectId,
        public array $sources,
    ) {
    }
}
