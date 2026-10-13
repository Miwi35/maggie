<?php

namespace Maggie\Finance\Tests\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\TransactionStatus;

/**
 * What the projection tests write on `recurring_projections.yaml`: confirmed
 * series, and the lines attached to their occurrences.
 *
 * @property EntityManagerInterface $em
 */
trait RecurringProjectionsWorld
{
    private function series(
        string $label,
        int $amountCents,
        string $anchorOn,
        string $accountRef = 'checking',
        string $categoryRef = 'housing',
        ?string $endsOn = null,
        RecurrencePeriod $period = RecurrencePeriod::Monthly,
    ): RecurringOperation {
        $account = $this->getFixture($accountRef);
        $category = $this->getFixture($categoryRef);
        \assert($account instanceof Account && $category instanceof Category);

        $operation = (new RecurringOperation())
            ->setLabel($label)
            ->setCounterpartyName($label)
            ->setCategory($category)
            ->setAccount($account)
            ->setUser($account->getUser())
            ->setPeriod($period)
            ->setAnchorOn(new \DateTimeImmutable($anchorOn))
            ->setReferenceAmountCents($amountCents)
            ->setEndsOn(null === $endsOn ? null : new \DateTimeImmutable($endsOn));

        $this->em->persist($operation);
        $this->em->flush();

        return $operation;
    }

    /** A line attached to the occurrence due on $occurrenceOn, whatever its status or its own day. */
    private function settle(RecurringOperation $operation, string $occurrenceOn, int $amountCents, TransactionStatus $status, ?string $bookedAt = null): Transaction
    {
        $transaction = (new Transaction())
            ->setUser($operation->getUser())
            ->setAccount($operation->getAccount())
            ->assignCategory($operation->getCategory(), CategorySource::Manual)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt ?? $occurrenceOn))
            ->setLabel($operation->getLabel())
            ->setStatus($status)
            ->attachToRecurring($operation, new \DateTimeImmutable($occurrenceOn), RecurringLinkSource::Manual);

        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }
}
