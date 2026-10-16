<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Message\SetMyShareCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\SetMyShare;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class SetMyShareHandler
{
    public function __construct(
        private readonly SetMyShare $setMyShare,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(SetMyShareCommand $command): PeerDebt
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        return $this->setMyShare->execute($transaction, $command->myShareCents, $command->shares, $command->object, $command->notes);
    }
}
