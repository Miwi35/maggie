<?php

namespace Maggie\Finance\Tests\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RecurringLinkSource;

/**
 * What the three « judged on the reference salary » tests add to
 * `reference_judgement.yaml`: payments for the measured series, and an extra
 * September spend.
 *
 * @property EntityManagerInterface $em
 */
trait ReferenceJudgementWorld
{
    /** Twelve payments of 4 950 €, so the measured user's mark is 4 950 €. */
    private function measureTheSalary(): void
    {
        /** @var RecurringOperation $series */
        $series = $this->getFixture('measured_salary_op');
        /** @var Account $account */
        $account = $this->getFixture('measured_checking');

        for ($monthsAgo = 12; $monthsAgo >= 1; --$monthsAgo) {
            $occurrenceOn = new \DateTimeImmutable(sprintf('first day of this month -%d months +24 days', $monthsAgo));

            $payment = new Transaction();
            $payment->setUser($account->getUser());
            $payment->setAccount($account);
            $payment->setAmountCents(495000);
            $payment->setBookedAt($occurrenceOn);
            $payment->setLabel('VIR GLOBEX');
            $payment->setCounterpartyName('GLOBEX');
            $payment->attachToRecurring($series, $occurrenceOn, RecurringLinkSource::Manual);
            $this->em->persist($payment);
        }

        $this->flushWithoutTransactionEffects($this->em);
    }

    /** A debit in September 2026, in the user's groceries or, by default, in no category. */
    private function spendInSeptember(string $user, int $cents, bool $inGroceries = false): void
    {
        /** @var User $owner */
        $owner = $this->getFixture($user.'_user');
        /** @var Account $account */
        $account = $this->getFixture($user.'_checking');

        $spend = new Transaction();
        $spend->setUser($owner);
        $spend->setAccount($account);
        $spend->setAmountCents(-$cents);
        $spend->setBookedAt(new \DateTimeImmutable('2026-09-20'));
        $spend->setLabel('Achat');
        if ($inGroceries) {
            $spend->assignCategory($this->getFixture($user.'_food'), CategorySource::Manual);
        }
        $this->em->persist($spend);

        $this->flushWithoutTransactionEffects($this->em);
    }
}
