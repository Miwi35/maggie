<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Forgets a contact: their transfers are detached one by one rather than by
 * the database's SET NULL, so each line's Mercure update and search document
 * stop naming someone who is gone; their counterparties go with them.
 */
class DeleteContact
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly TransactionRepository $transactionRepository,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
    ) {
    }

    public function execute(Contact $contact): void
    {
        foreach ($this->transactionRepository->findByContacts([$contact]) as $transaction) {
            $transaction->setContact(null);
        }
        foreach ($this->counterpartyRepository->findByContact($contact) as $counterparty) {
            $this->em->remove($counterparty);
        }
        $this->em->remove($contact);
        $this->em->flush();
    }
}
