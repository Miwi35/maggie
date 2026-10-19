<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * Records where the user stands in the review of a month (MAG-456): the step
 * reached, when given, and transactions handled, added to those already there.
 */
final readonly class SetReviewProgressCommand
{
    /**
     * @param \DateTimeImmutable $month                 the first day of the month reviewed
     * @param list<string>       $handledTransactionIds
     */
    public function __construct(
        public string $userId,
        public \DateTimeImmutable $month,
        public ?string $step = null,
        public array $handledTransactionIds = [],
    ) {
    }
}
