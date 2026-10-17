<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Message\RecordDebtCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\RecordDebt;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RecordDebtHandler
{
    public function __construct(
        private readonly RecordDebt $recordDebt,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(RecordDebtCommand $command): PeerDebt
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        return $this->recordDebt->execute($transaction, $command->contactId, $command->amountCents, $command->object, $command->notes);
    }
}
