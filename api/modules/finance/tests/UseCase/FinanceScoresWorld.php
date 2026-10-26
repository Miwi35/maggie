<?php

namespace Maggie\Finance\Tests\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;

/**
 * October 2026 of `finance_scores.yaml`: 1 500 € of monthly budget, rent
 * 700 € on the 5th and energy 80 € on the 10th (paid), and a 30 € gym on the
 * 20th (a category with no envelope). The rhythm expects the recurring debits
 * only: 780 € on the 13th, 810 € at the end of the month.
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

    /** A line no category claims: a debit by default, a credit with a positive amount. */
    private function leaveUnclassified(string $day, int $cents, TransferKind $kind = TransferKind::None, TransactionStatus $status = TransactionStatus::Spent, string $as = 'scores_user'): void
    {
        $line = new Transaction();
        $line->setUser($this->getFixture($as));
        $line->setAccount($this->getFixture('scores_checking'));
        $line->setAmountCents($cents);
        $line->setLabel('Sans catégorie');
        $line->setBookedAt(new \DateTimeImmutable($day));
        $line->setStatus($status);
        $line->setTransferKind($kind);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($line);
        $this->flushWithoutTransactionEffects($em);
    }
}
