<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

use Maggie\Finance\Entity\Transaction;

/** What the rules and the detections read has changed: the label, the amount, the date or the account. */
final readonly class HasDetectionRelevantChange
{
    private const array FIELDS = ['label', 'amountCents', 'bookedAt', 'account'];

    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Transaction $transaction): bool
    {
        return [] !== $this->changedFields();
    }

    /** @return list<string> */
    public function changedFields(): array
    {
        $changed = [];
        foreach (self::FIELDS as $field) {
            if (isset($this->changeSet[$field]) && !self::same($this->changeSet[$field][0], $this->changeSet[$field][1])) {
                $changed[] = $field;
            }
        }

        return $changed;
    }

    private static function same(mixed $before, mixed $after): bool
    {
        if ($before instanceof \DateTimeInterface && $after instanceof \DateTimeInterface) {
            return $before->getTimestamp() === $after->getTimestamp();
        }

        return $before === $after;
    }
}
