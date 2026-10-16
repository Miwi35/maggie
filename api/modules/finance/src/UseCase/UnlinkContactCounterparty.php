<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\RelinkContactTransactionsCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** Removes a counterparty, then detaches the transfers only it recognised. */
class UnlinkContactCounterparty
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function execute(ContactCounterparty $counterparty): void
    {
        $userId = (string) $counterparty->getUser()->getId();

        $this->em->remove($counterparty);
        $this->em->flush();

        $this->bus->dispatch(new RelinkContactTransactionsCommand($userId));
    }
}
