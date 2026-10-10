<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Exception\RecurringAttachmentException;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\RecurringOperationGuard;
use Maggie\Finance\Service\TransactionNatureGuard;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Maggie\Finance\UseCase\UpdateTransaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTransactionHandler
{
    public function __construct(
        private readonly UpdateTransaction $updateTransaction,
        private readonly TransactionRepository $transactionRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly TransactionNatureGuard $natureGuard,
        private readonly AttachRecurringTransactions $attachRecurring,
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
            // The counterparty follows the label while it equals what the label
            // yields. A bank name that differs from it stays; one that happens
            // to equal it (a label that was the creditor) cannot be told apart.
            $followsLabel = $transaction->getCounterpartyName() === MerchantExtractor::extract($transaction->getLabel());
            $transaction->setLabel($command->label);
            if ($followsLabel) {
                $transaction->setCounterpartyName(MerchantExtractor::extract($command->label));
            }
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

        $this->natureGuard->assertStillCompatible($transaction);

        $this->applyTransfer($transaction, $command);
        $this->applyRecurring($transaction, $command);

        // A line a marking made neutral settles no occurrence any more, on
        // either leg.
        foreach ([$transaction, $transaction->getCounterpart()] as $line) {
            if (null !== $line) {
                $this->attachRecurring->releaseNeutral($line);
            }
        }

        return $this->updateTransaction->execute($transaction);
    }

    /**
     * An attachment to a recurring operation, by hand: sealed `manual`, so
     * the automatic pass never undoes it.
     *
     * @return list<RecurringOperation> the series whose attachments changed
     */
    private function applyRecurring(Transaction $transaction, UpdateTransactionCommand $command): array
    {
        if ($command->clears('recurringOperation')) {
            return $this->attachRecurring->detachByHand($transaction);
        }

        if (null === $command->recurringOperationId && null === $command->recurringOccurrenceOn) {
            return [];
        }

        try {
            $operation = null !== $command->recurringOperationId
                ? $this->references->recurringOperation($command->recurringOperationId, $transaction->getUser())
                : $transaction->getRecurringOperation()
                    ?? throw new \DomainException('recurringOccurrenceOn moves a line within its recurring operation: name the recurringOperationId to attach it.');
            $occurrenceOn = null === $command->recurringOccurrenceOn
                ? null
                : RecurringOperationGuard::date($command->recurringOccurrenceOn, 'recurringOccurrenceOn');
        } catch (\DomainException $e) {
            // A refused request, like the attachment's own refusals: 422.
            throw new RecurringAttachmentException($e->getMessage(), 0, $e);
        }

        return $this->attachRecurring->attachByHand($transaction, $operation, $occurrenceOn);
    }

    /**
     * A transfer or rejection marking, from the detection or from the user's hand.
     *
     * `transferKind` without a `counterpartId` marks a single-legged transfer,
     * which is the normal case when only one of the two accounts is synced.
     * The source defaults to `manual`, as `categorySource` does: the only
     * caller that knows better — the detection — says so.
     */
    private function applyTransfer(Transaction $transaction, UpdateTransactionCommand $command): void
    {
        if (null === $command->transferKind) {
            if (!$command->clears('transferKind')) {
                return;
            }

            $transaction->releaseInternalTransfer(TransferSource::Manual);

            return;
        }

        $kind = TransferKind::from($command->transferKind);
        $source = TransferSource::from($command->transferSource ?? TransferSource::Manual->value);

        if (TransferKind::None === $kind) {
            $transaction->releaseInternalTransfer($source);

            return;
        }

        if (null === $command->counterpartId) {
            // A single leg contradicts whatever pairing was on this line.
            $transaction->releaseInternalTransfer($source)->setTransferKind($kind);

            return;
        }

        if ($command->counterpartId === (string) $transaction->getId()) {
            throw new \DomainException('A transaction cannot be its own counterpart.');
        }

        $counterpart = $this->references->transaction($command->counterpartId, $transaction->getUser(), 'Counterpart');

        $sameAccount = $counterpart->getAccount()->getId()->equals($transaction->getAccount()->getId());
        if (TransferKind::Internal === $kind && $sameAccount) {
            throw new \DomainException('An internal transfer goes between two different accounts.');
        }
        // The bank gives a rejected payment back where it took it from.
        if (TransferKind::Rejected === $kind && !$sameAccount) {
            throw new \DomainException('A rejection is credited back on the account of the payment it cancels.');
        }

        $transaction->pairWith($counterpart, $kind, $source);
    }
}
