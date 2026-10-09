<?php

namespace Maggie\Finance\Tests\Specification;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Specification\IsNewTransaction;
use PHPUnit\Framework\TestCase;

class IsNewTransactionTest extends TestCase
{
    public function testAnInsertGoesFromNoAccountToAnAccount(): void
    {
        self::assertTrue((new IsNewTransaction(['account' => [null, 'checking'], 'label' => [null, 'PAIEMENT']]))->isSatisfiedBy(new Transaction()));
    }

    public function testAnUpdateOfAStoredTransactionIsNotNew(): void
    {
        self::assertFalse((new IsNewTransaction(['label' => ['A', 'B']]))->isSatisfiedBy(new Transaction()));
        self::assertFalse((new IsNewTransaction(['account' => ['checking', 'savings']]))->isSatisfiedBy(new Transaction()));
        self::assertFalse((new IsNewTransaction([]))->isSatisfiedBy(new Transaction()));
    }
}
