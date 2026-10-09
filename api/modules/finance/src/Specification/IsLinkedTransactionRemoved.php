<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

use Maggie\Finance\Entity\Transaction;

/** The transaction being removed was paired with another one, which must not keep claiming a pair that is gone. */
final readonly class IsLinkedTransactionRemoved
{
    public function isSatisfiedBy(Transaction $transaction): bool
    {
        return null !== $transaction->getCounterpart();
    }
}
