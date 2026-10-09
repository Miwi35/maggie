<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\ReleaseCounterpartCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * The leg left behind by a deleted one goes back to being an ordinary
 * transaction: it was excluded from every figure only because of its pair.
 */
#[AsMessageHandler]
class ReleaseCounterpartHandler
{
    public function __construct(
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(ReleaseCounterpartCommand $command): ?Transaction
    {
        $leg = $this->transactionRepository->find($command->transactionId);
        if (null === $leg || TransferKind::None === $leg->getTransferKind()) {
            return null;
        }

        // Paired again with another line since: that pair is not ours to undo.
        $pairedWith = $leg->getCounterpart();
        if (null !== $pairedWith && (string) $pairedWith->getId() !== $command->removedTransactionId) {
            return null;
        }

        $leg->releaseInternalTransfer(TransferSource::Auto);

        return $this->updateTransaction->execute($leg);
    }
}
