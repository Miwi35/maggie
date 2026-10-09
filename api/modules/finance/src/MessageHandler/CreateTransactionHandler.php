<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\TransactionNatureGuard;
use Maggie\Finance\UseCase\CreateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTransactionHandler
{
    public function __construct(
        private readonly CreateTransaction $createTransaction,
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
        }

        // Categorization, transfer and rejection follow from the insert itself
        // (TransactionRecorded), whichever door created the line.
        return $this->createTransaction->execute($transaction);
    }
}
