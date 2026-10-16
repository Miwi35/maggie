<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\UnlinkContactCounterpartyCommand;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\UseCase\UnlinkContactCounterparty;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UnlinkContactCounterpartyHandler
{
    public function __construct(
        private readonly UnlinkContactCounterparty $unlinkContactCounterparty,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
    ) {
    }

    public function __invoke(UnlinkContactCounterpartyCommand $command): void
    {
        $counterparty = $this->counterpartyRepository->findOneBy(['id' => $command->counterpartyId, 'user' => $command->userId])
            ?? throw new \DomainException("Counterparty not found: {$command->counterpartyId}");

        $this->unlinkContactCounterparty->execute($counterparty);
    }
}
