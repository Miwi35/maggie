<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\CategorizeTransaction;
use Maggie\Finance\UseCase\CreateTransaction;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateTransactionHandler
{
    public function __construct(
        private readonly CreateTransaction $createTransaction,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly DetectInternalTransfers $detectInternalTransfers,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(CreateTransactionCommand $command): Transaction
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $account = $this->references->account($command->accountId, $user);

        $transaction = new Transaction();
        $transaction->setUser($user);
        $transaction->setAccount($account);
        $transaction->setAmountCents($command->amountCents);
        $transaction->setLabel($command->label);
        $transaction->setBookedAt(new \DateTimeImmutable($command->bookedAt));
        $transaction->setStatus(TransactionStatus::from($command->status));
        $transaction->setCurrency($command->currency);
        $transaction->setIsExceptional($command->isExceptional);

        if (null !== $command->categoryId) {
            $category = $this->references->category($command->categoryId, $user);
            $transaction->assignCategory($category, CategorySource::Manual);
        } else {
            $this->categorizeTransaction->apply($transaction);
        }

        // A movement between two of the owner's own accounts is recognised as
        // it lands, like a rule claiming a category: the second leg of a
        // transfer is often imported minutes after the first.
        $counterpart = $this->detectInternalTransfers->detectFor($transaction);
        if (null !== $counterpart) {
            $transaction->markAsInternalTransfer($counterpart, TransferSource::Auto);
        }

        $transaction = $this->createTransaction->execute($transaction);

        // The other leg changed too, and only a command of its own republishes
        // it: without this, the search index — which is what the transaction
        // list reads — would still call it an ordinary expense.
        if (null !== $counterpart) {
            $this->bus->dispatch(new UpdateTransactionCommand(
                userId: (string) $user->getId(),
                transactionId: (string) $counterpart->getId(),
                transferKind: TransferKind::Internal->value,
                transferSource: TransferSource::Auto->value,
                counterpartId: (string) $transaction->getId(),
            ));
        }

        return $transaction;
    }
}
