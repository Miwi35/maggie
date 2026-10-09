<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;

/**
 * What a categorization rule or a recurring operation recognises a
 * transaction by. The counterparty comes first; the label pattern stands in
 * when one side has no counterparty to compare.
 */
final readonly class MatchCriteria
{
    /**
     * @param ?int $minAmountCents absolute bound, in cents
     * @param ?int $maxAmountCents absolute bound, in cents
     */
    public function __construct(
        public ?string $counterpartyKey = null,
        public ?string $labelPattern = null,
        public MatchType $matchType = MatchType::Contains,
        public AmountDirection $direction = AmountDirection::Any,
        public ?int $minAmountCents = null,
        public ?int $maxAmountCents = null,
    ) {
    }
}
