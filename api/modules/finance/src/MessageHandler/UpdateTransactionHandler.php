<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTransactionHandler
{
    public function __construct(
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
        private readonly OwnedReferenceResolver $references,
    ) {
    }

    public function __invoke(UpdateTransactionCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->findOneBy(['id' => $command->transactionId, 'user' => $command->userId])
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        if (null !== $command->accountId) {
            $account = $this->references->account($command->accountId, $transaction->getUser());
            $transaction->setAccount($account);
        }
        if (null !== $command->amountCents) {
            $transaction->setAmountCents($command->amountCents);
        }
        if (null !== $command->label) {
            $transaction->setLabel($command->label);
        }
        if (null !== $command->bookedAt) {
            $transaction->setBookedAt(new \DateTimeImmutable($command->bookedAt));
        }
        if (null !== $command->status) {
            $transaction->setStatus(TransactionStatus::from($command->status));
        }
        if (null !== $command->currency) {
            $transaction->setCurrency($command->currency);
        }
        if (null !== $command->isExceptional) {
            $transaction->setIsExceptional($command->isExceptional);
        }
        if (null !== $command->retrospect) {
            $transaction->setRetrospect(RetrospectVerdict::from($command->retrospect));
        }
        if (null !== $command->categoryId) {
            // '' is the historical way to empty the category; clearFields is the explicit one.
            if ('' === $command->categoryId) {
                $transaction->assignCategory(null, CategorySource::None);
            } else {
                $category = $this->references->category($command->categoryId, $transaction->getUser());
                $transaction->assignCategory(
                    $category,
                    CategorySource::from($command->categorySource ?? CategorySource::Manual->value),
                );
            }
        } elseif ($command->clears('categoryId')) {
            $transaction->assignCategory(null, CategorySource::None);
        }

        $this->applyTransfer($transaction, $command);

        return $this->updateTransaction->execute($transaction);
    }

    /**
     * A transfer marking, from the detection or from the user's hand.
     *
     * `transferKind` without a `counterpartId` marks a single-legged transfer,
     * which is the normal case when only one of the two accounts is synced.
     * The source defaults to `manual`, as `categorySource` does: the only
     * caller that knows better — the detection — says so.
     */
    private function applyTransfer(Transaction $transaction, UpdateTransactionCommand $command): void
    {
        if (null === $command->transferKind) {
            if ($command->clears('transferKind')) {
                $transaction->releaseInternalTransfer(TransferSource::Manual);
            }

            return;
        }

        $kind = TransferKind::from($command->transferKind);
        $source = TransferSource::from($command->transferSource ?? TransferSource::Manual->value);

        if (TransferKind::None === $kind) {
            $transaction->releaseInternalTransfer($source);

            return;
        }

        if (null === $command->counterpartId) {
            $transaction->setTransferKind($kind)->setTransferSource($source);

            return;
        }

        if ($command->counterpartId === (string) $transaction->getId()) {
            throw new \DomainException('A transaction cannot be its own counterpart.');
        }

        $counterpart = $this->references->transaction($command->counterpartId, $transaction->getUser(), 'Counterpart');

        if ($counterpart->getAccount()->getId()->equals($transaction->getAccount()->getId())) {
            throw new \DomainException('An internal transfer goes between two different accounts.');
        }

        $transaction->markAsInternalTransfer($counterpart, $source);
    }
}
