<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Resolves the entities a command points to by id, only when they belong to
 * the given owner. Someone else's object is reported exactly like a missing
 * one, so the refusal does not reveal that it exists.
 */
class OwnedReferenceResolver
{
    public function __construct(
        private readonly AccountRepository $accountRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $recurringOperationRepository,
        private readonly EnvelopeRepository $envelopeRepository,
    ) {
    }

    public function account(string $id, User $owner): Account
    {
        $account = $this->accountRepository->find($id);

        if (!$account instanceof Account || !$account->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("Account not found: {$id}");
        }

        return $account;
    }

    public function transaction(string $id, User $owner, string $label = 'Transaction'): Transaction
    {
        $transaction = $this->transactionRepository->find($id);

        if (!$transaction instanceof Transaction || !$transaction->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("{$label} not found: {$id}");
        }

        return $transaction;
    }

    public function category(string $id, User $owner, string $label = 'Category'): Category
    {
        $category = $this->categoryRepository->find($id);

        if (!$category instanceof Category || !$category->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("{$label} not found: {$id}");
        }

        return $category;
    }

    public function envelope(string $id, User $owner): Envelope
    {
        $envelope = Ulid::isValid($id) ? $this->envelopeRepository->find($id) : null;

        if (!$envelope instanceof Envelope || !$envelope->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("Envelope not found: {$id}");
        }

        return $envelope;
    }

    /** Any operation, with or without recurrence. */
    public function operation(string $id, User $owner): RecurringOperation
    {
        $operation = Ulid::isValid($id) ? $this->recurringOperationRepository->find($id) : null;
        if (!$operation instanceof RecurringOperation || !$operation->getUser()->getId()->equals($owner->getId())) {
            throw new \DomainException("Operation not found: {$id}");
        }

        return $operation;
    }

    /** A series: an operation with a recurrence, the only kind a line can be an occurrence of. */
    public function recurringOperation(string $id, User $owner): RecurringOperation
    {
        $operation = Ulid::isValid($id) ? $this->recurringOperationRepository->find($id) : null;
        if (!$operation instanceof RecurringOperation || !$operation->getUser()->getId()->equals($owner->getId()) || !$operation->hasRecurrence()) {
            throw new \DomainException("Recurring operation not found: {$id}");
        }

        return $operation;
    }

    /** An operation without recurrence: what a categorization rule was. */
    public function operationWithoutRecurrence(string $id, User $owner): RecurringOperation
    {
        $operation = Ulid::isValid($id) ? $this->recurringOperationRepository->find($id) : null;
        if (!$operation instanceof RecurringOperation || !$operation->getUser()->getId()->equals($owner->getId()) || $operation->hasRecurrence()) {
            throw new \DomainException("Operation without recurrence not found: {$id}");
        }

        return $operation;
    }
}
