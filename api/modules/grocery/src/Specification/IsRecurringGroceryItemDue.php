<?php

declare(strict_types=1);

namespace Maggie\Grocery\Specification;

use Maggie\Grocery\Entity\RecurringGroceryItem;

/** A recurring item is wanted again: never added, or its period has run out since the day it last was. */
final readonly class IsRecurringGroceryItemDue
{
    private \DateTimeImmutable $day;

    public function __construct(\DateTimeImmutable $day)
    {
        // `lastAddedAt` is a date: the time of day must not tip a comparison.
        $this->day = $day->setTime(0, 0);
    }

    /** The shopper's today, whatever the clock of the machine that asks. */
    public static function today(): self
    {
        return new self(new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')));
    }

    public function day(): \DateTimeImmutable
    {
        return $this->day;
    }

    public function isSatisfiedBy(RecurringGroceryItem $item): bool
    {
        $lastAddedAt = $item->getLastAddedAt();

        if (null === $lastAddedAt) {
            return true;
        }

        // Both are calendar days: Doctrine hydrates the date at midnight in PHP's
        // zone (UTC) while the day is Paris's, two hours earlier in UTC, so
        // comparing the instants would hold a weekly item back a day.
        $lastDay = new \DateTimeImmutable($lastAddedAt->format('Y-m-d'), $this->day->getTimezone());

        return $lastDay->add($item->getFrequency()->interval()) <= $this->day;
    }
}
