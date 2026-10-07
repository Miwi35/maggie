<?php

namespace Maggie\Finance\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Maggie\Finance\Service\TransactionNatureGuard;
use PHPUnit\Framework\TestCase;

class TransactionNatureGuardTest extends TestCase
{
    private TransactionNatureGuard $guard;

    protected function setUp(): void
    {
        $this->guard = new TransactionNatureGuard($this->createStub(EntityManagerInterface::class));
    }

    private function category(ObligationFlag $obligation): Category
    {
        return (new Category())->setObligation($obligation);
    }

    public function testNoCategoryOrAZeroAmountIsAlwaysFine(): void
    {
        $this->guard->assertCompatible(-100, null);
        $this->guard->assertCompatible(0, $this->category(ObligationFlag::Income));
        $this->guard->assertCompatible(0, $this->category(ObligationFlag::Mandatory));
        $this->addToAssertionCount(1);
    }

    public function testMatchingNaturesPass(): void
    {
        $this->guard->assertCompatible(5000, $this->category(ObligationFlag::Income));
        foreach ([ObligationFlag::Mandatory, ObligationFlag::Optional, ObligationFlag::Saving, ObligationFlag::Investment, ObligationFlag::Debt] as $flag) {
            $this->guard->assertCompatible(-5000, $this->category($flag));
        }
        $this->addToAssertionCount(1);
    }

    public function testAnIncomeCategoryOnAnExpenseIsRefused(): void
    {
        $this->expectException(IncompatibleCategoryException::class);
        $this->guard->assertCompatible(-5000, $this->category(ObligationFlag::Income));
    }

    public function testAnExpenseCategoryOnAnIncomeIsRefused(): void
    {
        $this->expectException(IncompatibleCategoryException::class);
        $this->guard->assertCompatible(5000, $this->category(ObligationFlag::Optional));
    }
}
