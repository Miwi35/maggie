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

        if (null !== $command->accountId) {
            $account = $this->accountRepository->find($command->accountId)
                ?? throw new \DomainException("Account not found: {$command->accountId}");
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
                $category = $this->categoryRepository->find($command->categoryId)
                    ?? throw new \DomainException("Category not found: {$command->categoryId}");
                $transaction->assignCategory(
                    $category,
                    CategorySource::from($command->categorySource ?? CategorySource::Manual->value),
                );
            }
        } elseif ($command->clears('categoryId')) {
            $transaction->assignCategory(null, CategorySource::None);
        }

        return $this->updateTransaction->execute($transaction);
    }
}
