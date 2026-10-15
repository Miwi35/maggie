<?php

namespace Maggie\Finance\Tests\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;

/**
 * October 2026 of `finance_scores.yaml`: 1 500 € of monthly budget, rent
 * 700 € on the 5th and energy 80 € on the 10th (paid), so 720 € of variable
 * part over 31 days. On the 13th the pace expects 780 + 720 × 13 / 31 ≈
 * 1 081,94 € to have been spent.
 *
 * @property EntityManagerInterface $em
 */
trait FinanceScoresWorld
{
    private function financeScoresFixture(): string
    {
        return __DIR__.'/fixtures/finance_scores.yaml';
    }

    private function spendGroceries(string $day, int $cents, string $category = 'scores_food', TransactionStatus $status = TransactionStatus::Spent): void
    {
        $spend = new Transaction();
        $spend->setUser($this->getFixture('scores_user'));
        $spend->setAccount($this->getFixture('scores_checking'));
        $spend->setCategory($this->getFixture($category));
        $spend->setAmountCents(-$cents);
        $spend->setLabel('Achat');
        $spend->setBookedAt(new \DateTimeImmutable($day));
        $spend->setStatus($status);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($spend);
        $this->flushWithoutTransactionEffects($em);
    }
}
