<?php

namespace Maggie\Finance\Tests\Specification;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Specification\IsLinkedTransactionRemoved;
use PHPUnit\Framework\TestCase;

class IsLinkedTransactionRemovedTest extends TestCase
{
    public function testAPairedLegIsLinked(): void
    {
        $out = new Transaction();
        $out->markAsInternalTransfer(new Transaction(), TransferSource::Auto);

        self::assertTrue((new IsLinkedTransactionRemoved())->isSatisfiedBy($out));
    }

    public function testAnOrdinaryOrSingleLeggedTransactionIsNot(): void
    {
        self::assertFalse((new IsLinkedTransactionRemoved())->isSatisfiedBy(new Transaction()));
    }
}
