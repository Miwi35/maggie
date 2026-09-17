<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTransactionHandler
{
    public function __construct(
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function __invoke(UpdateTransactionCommand $command): Transaction
    {
        $transaction = $this->transactionRepository->find($command->transactionId)
            ?? throw new \DomainException("Transaction not found: {$command->transactionId}");

        if ($command->accountId !== null) {
            $account = $this->accountRepository->find($command->accountId)
                ?? throw new \DomainException("Account not found: {$command->accountId}");
            $transaction->setAccount($account);
        }
        if ($command->amountCents !== null) {
            $transaction->setAmountCents($command->amountCents);
        }
        if ($command->label !== null) {
            $transaction->setLabel($command->label);
        }
        if ($command->bookedAt !== null) {
            $transaction->setBookedAt(new \DateTimeImmutable($command->bookedAt));
        }
        if ($command->status !== null) {
            $transaction->setStatus(TransactionStatus::from($command->status));
        }
        if ($command->currency !== null) {
            $transaction->setCurrency($command->currency);
        }
        if ($command->isExceptional !== null) {
            $transaction->setIsExceptional($command->isExceptional);
        }
        if ($command->retrospect !== null) {
            $transaction->setRetrospect(RetrospectVerdict::from($command->retrospect));
        }
        if ($command->categoryId !== null) {
            if ($command->categoryId === '') {
                $transaction->assignCategory(null, CategorySource::None);
            } else {
                $category = $this->categoryRepository->find($command->categoryId)
                    ?? throw new \DomainException("Category not found: {$command->categoryId}");
                $transaction->assignCategory(
                    $category,
                    CategorySource::from($command->categorySource ?? CategorySource::Manual->value),
                );
            }
        }

        return $this->updateTransaction->execute($transaction);
    }
}
