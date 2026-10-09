<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\TransactionNatureGuard;
use Maggie\Finance\UseCase\CategorizeTransaction;
use Maggie\Finance\UseCase\CreateTransaction;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\DetectRejections;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTransactionHandler
{
    public function __construct(
        private readonly CreateTransaction $createTransaction,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly DetectInternalTransfers $detectInternalTransfers,
        private readonly DetectRejections $detectRejections,
        private readonly OwnedReferenceResolver $references,
        private readonly TransactionNatureGuard $natureGuard,
        private readonly UserRepository $userRepository,
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
        $transaction->setCounterpartyName(MerchantExtractor::extract($command->label));
        $transaction->setBookedAt(new \DateTimeImmutable($command->bookedAt));
        $transaction->setStatus(TransactionStatus::from($command->status));
        $transaction->setCurrency($command->currency);
        $transaction->setIsExceptional($command->isExceptional);

        if (null !== $command->categoryId) {
            $category = $this->references->category($command->categoryId, $user);
            $this->natureGuard->assertCompatible($command->amountCents, $category);
            $transaction->assignCategory($category, CategorySource::Manual);
        } else {
            $this->categorizeTransaction->apply($transaction);
        }

        // A rejected payment first: its credit is the exact opposite of the
        // debit it gives back, and must never be taken for a transfer. Only a
        // new credit is matched — a debit created after its rejection waits
        // for the next sync or for `app:finance:detect-rejections`.
        $rejected = $this->detectRejections->detectFor($transaction);
        if (null !== $rejected) {
            $rejected->markAsRejection($transaction, TransferSource::Auto);
        }

        // A movement between two of the owner's own accounts is recognised as
        // it lands, like a rule claiming a category: the second leg of a
        // transfer is often imported minutes after the first.
        if (null === $rejected) {
            $counterpart = $this->detectInternalTransfers->detectFor($transaction);
            $counterpart?->markAsInternalTransfer($transaction, TransferSource::Auto);
        }

        $transaction = $this->createTransaction->execute($transaction);

        if (null !== $rejected) {
            $this->detectRejections->notify($rejected, $transaction);
        }

        return $transaction;
    }
}
