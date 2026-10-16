<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * « Ses comptes » on a contact's page: how many transfers each counterparty
 * recognises and when the last one was booked. Counted on the lines attached
 * to the contact, by the same match that attached them.
 */
class MeasureCounterpartyUsage
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @param iterable<ContactCounterparty> $counterparties filled in place */
    public function fill(iterable $counterparties): void
    {
        $list = [];
        $contacts = [];
        foreach ($counterparties as $counterparty) {
            $list[] = $counterparty;
            $contacts[(string) $counterparty->getContact()->getId()] = $counterparty->getContact();
        }
        if ([] === $list) {
            return;
        }

        $byContact = [];
        foreach ($this->transactionRepository->findByContacts(array_values($contacts)) as $transaction) {
            $byContact[(string) $transaction->getContact()?->getId()][] = $transaction;
        }

        foreach ($list as $counterparty) {
            $count = 0;
            $last = null;
            foreach ($byContact[(string) $counterparty->getContact()->getId()] ?? [] as $transaction) {
                if (!CounterpartyMatch::matches($transaction, $counterparty->getMatchKey())) {
                    continue;
                }
                ++$count;
                if (null === $last || $transaction->getBookedAt() > $last) {
                    $last = $transaction->getBookedAt();
                }
            }
            $counterparty->useUsage($count, $last);
        }
    }
}
