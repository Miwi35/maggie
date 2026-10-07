<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Exception\IncompatibleCategoryException;

/**
 * A transaction's nature is the sign of its amount; a category carries its own
 * (`income` or not). The two must agree when a line is written — reading a
 * line that predates the rule never goes through here.
 */
class TransactionNatureGuard
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** Whether a line of this amount may be filed under this category. */
    public function isCompatible(int $amountCents, Category $category): bool
    {
        if (0 === $amountCents) {
            return true;
        }

        return (ObligationFlag::Income === $category->getObligation()) === ($amountCents > 0);
    }

    /** @throws IncompatibleCategoryException when the category contradicts the sign of the amount */
    public function assertCompatible(int $amountCents, ?Category $category): void
    {
        if (null === $category || 0 === $amountCents) {
            return;
        }

        $isIncomeCategory = ObligationFlag::Income === $category->getObligation();

        if ($isIncomeCategory && $amountCents < 0) {
            throw new IncompatibleCategoryException('An income category cannot be used on an expense (negative amount). Pick an expense category, or make the amount positive.');
        }

        if (!$isIncomeCategory && $amountCents > 0) {
            throw new IncompatibleCategoryException('An expense category cannot be used on an income (positive amount). Pick an income category, or make the amount negative.');
        }
    }

    /**
     * Update: only a line whose amount sign or category is being changed is
     * checked, so an old inconsistent line can still be renamed or re-dated.
     * "Before" is what the database holds — a REST merge-patch has already
     * written the new values on the managed entity when this runs.
     *
     * @throws IncompatibleCategoryException when the category contradicts the sign of the amount
     */
    public function assertStillCompatible(Transaction $transaction): void
    {
        $amount = $transaction->getAmountCents();
        $category = $transaction->getCategory();

        $original = $this->em->getUnitOfWork()->getOriginalEntityData($transaction);
        $amountBefore = $original['amountCents'] ?? $amount;
        $categoryBefore = $original['category'] ?? null;

        $categoryUnchanged = $category?->getId()->toRfc4122() === $categoryBefore?->getId()->toRfc4122();
        $sameNature = ($amount <=> 0) === ($amountBefore <=> 0);

        if ($categoryUnchanged && $sameNature) {
            return;
        }

        $this->assertCompatible($amount, $category);
    }
}
