<?php

namespace Maggie\Finance\Tests\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;

/**
 * What the savings advances tests write on `savings_advances.yaml`: dated
 * movements, salaries, and transfers already paired.
 *
 * @property EntityManagerInterface $em
 */
trait SavingsAdvancesWorld
{
    private function user(string $ref = 'test_user'): User
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return $user;
    }

    /** One movement, flushed without the creation effects, so a query sees it. */
    private function add(string $accountRef, int $amountCents, string $bookedAt, ?string $categoryRef = null, string $label = 'Mouvement'): Transaction
    {
        $account = $this->getFixture($accountRef);
        \assert($account instanceof Account);

        $transaction = (new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel($label);

        if (null !== $categoryRef) {
            $category = $this->getFixture($categoryRef);
            \assert($category instanceof Category);
            $transaction->qualify(Qualification::none($category), null);
        }

        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }

    private function salary(int $amountCents, string $bookedAt): Transaction
    {
        return $this->add('checking', $amountCents, $bookedAt, 'salary', 'VIR ACME SALAIRE');
    }

    /**
     * A transfer from one account to another, both legs written and paired.
     *
     * @return array{Transaction, Transaction} the debit leg, then the credit leg
     */
    private function transfer(string $fromRef, string $toRef, int $amountCents, string $bookedAt, TransferKind $kind = TransferKind::Internal, TransferSource $source = TransferSource::Auto): array
    {
        $debit = $this->add($fromRef, -$amountCents, $bookedAt, label: 'Virement émis');
        $credit = $this->add($toRef, $amountCents, $bookedAt, label: 'Virement reçu');
        $debit->pairWith($credit, $kind, $source);
        $this->flushWithoutTransactionEffects($this->em);

        return [$debit, $credit];
    }

    /** Gives back part of an advance: a checking → source transfer whose debit leg points at it. */
    private function repay(Transaction $advance, string $sourceRef, int $amountCents, string $bookedAt): Transaction
    {
        [$debit] = $this->transfer('checking', $sourceRef, $amountCents, $bookedAt, TransferKind::Repayment);
        $debit->setRepays($advance);
        $this->flushWithoutTransactionEffects($this->em);

        return $debit;
    }
}
