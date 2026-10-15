<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;

/**
 * What a categorization rule or a recurring operation recognises a
 * transaction by. The counterparty comes first; the label pattern stands in
 * when one side has no counterparty to compare. With neither, a direction and
 * a bounded amount recognise it alone (« an income of 4 750 € ± 10 % »).
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

    public function hasIdentity(): bool
    {
        return (null !== $this->counterpartyKey && '' !== $this->counterpartyKey)
            || (null !== $this->labelPattern && '' !== $this->labelPattern);
    }

    /** A bound alone, without a direction, would claim every line of either sign. */
    public function hasDirectedAmount(): bool
    {
        return AmountDirection::Any !== $this->direction
            && (null !== $this->minAmountCents || null !== $this->maxAmountCents);
    }

    /** Never an empty criterion: it would claim every transaction. */
    public function isRecognisable(): bool
    {
        return $this->hasIdentity() || $this->hasDirectedAmount();
    }
}
