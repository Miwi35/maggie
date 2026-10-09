<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

use Maggie\Finance\Entity\Transaction;

/** The transaction is being inserted: its account, which a stored transaction always has, goes from nothing to something. */
final readonly class IsNewTransaction
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Transaction $transaction): bool
    {
        return isset($this->changeSet['account']) && null === $this->changeSet['account'][0];
    }
}
