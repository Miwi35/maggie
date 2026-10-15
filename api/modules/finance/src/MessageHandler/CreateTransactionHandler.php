<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\CreateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTransactionHandler
{
    public function __construct(
        private readonly CreateTransaction $createTransaction,
        private readonly OwnedReferenceResolver $references,
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
        Transaction::assertNoteFits($command->note);
        $transaction->setNote($command->note);

        $category = null;
        if (null !== $command->categoryId) {
            $category = $this->references->category($command->categoryId, $user);
        }
        // A line entered by hand is qualified by hand; a category alone carries
        // what its deprecated flag meant, until MAG-479 has the clients send one.
        if (null !== $command->qualification) {
            $transaction->qualify(Qualification::fromArray($command->qualification, $category), null);
        } elseif (null !== $category) {
            $transaction->qualify(Qualification::fromCategory($category), null);
        }

        // Categorization, transfer and rejection follow from the insert itself
        // (TransactionRecorded), whichever door created the line.
        return $this->createTransaction->execute($transaction);
    }
}
