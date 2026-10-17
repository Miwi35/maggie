<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Exception\OneOffAttributionException;
use Maggie\Finance\Exception\RecurringAttachmentException;
use Maggie\Finance\Exception\SatisfactionException;
use Maggie\Finance\Exception\SharedSpendException;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\PeerDebtRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\RecurringOperationGuard;
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
        private readonly AttachRecurringTransactions $attachRecurring,
        private readonly PeerDebtRepository $peerDebtRepository,
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
        if (null !== $command->note) {
            Transaction::assertNoteFits($command->note);
            $transaction->setNote($command->note);
        } elseif ($command->clears('note')) {
            $transaction->setNote(null);
        }
        $verdict = null === $command->retrospect ? null : RetrospectVerdict::from($command->retrospect);
        if (null !== $verdict) {
            $transaction->setRetrospect($verdict);
        }
        $this->applyQualification($transaction, $command);

        $this->applyTransfer($transaction, $command);
        // After the marking and the amount: a shared spend stays one whose part is below it.
        $this->assertShareStillFits($transaction);
        $this->assertDebtStillFits($transaction);
        // After the marking and the amount: what is judged is what the line has become.
        if (null !== $verdict && RetrospectVerdict::Unrated !== $verdict && !$transaction->canBeJudged()) {
            throw new SatisfactionException('A satisfaction goes to a spend only: an income or a transfer between your own accounts has nothing to judge.');
        }
        // After the marking: a line made a transfer in the same update is no spend to attribute.
        $this->applyOneOffEnvelope($transaction, $command);
        $this->applyRepays($transaction, $command);
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
     * A shared spend made an income, a transfer, or smaller than the part kept
     * would leave a créance for money nobody advanced: the share goes first.
     */
    private function assertShareStillFits(Transaction $transaction): void
    {
        $myShare = $transaction->getMyShareCents();
        if (null !== $myShare && (!$transaction->canBeShared() || $myShare >= abs($transaction->getAmountCents()))) {
            throw new SharedSpendException('This spend is shared: remove the share (DELETE /api/finance/transactions/{id}/my-share) before making it an income, a transfer, or smaller than your part.');
        }
    }

    /**
     * A received transfer declared « à rembourser » stays an income at least as
     * big as the amount to give back: otherwise the dette would be money nobody
     * advanced.
     */
    private function assertDebtStillFits(Transaction $transaction): void
    {
        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null !== $debt && PeerDebtDirection::IOwe === $debt->getDirection()
            && (!$transaction->canBeDebt() || $debt->getAmountCents() > $transaction->getAmountCents())) {
            throw new SharedSpendException('This transfer is declared as a dette: remove it (DELETE /api/finance/transactions/{id}/debt) before making it a spend, a transfer, or smaller than the amount to give back.');
        }
    }

    /**
     * Qualifies the line with a rule's whole qualification, or with the
     * owner's own — never field by field: what is not given is the current
     * category, while a category given alone carries the qualification its
     * deprecated flag meant (until MAG-479 has the clients send one).
     */
    private function applyQualification(Transaction $transaction, UpdateTransactionCommand $command): void
    {
        if (null !== $command->qualifiedByRuleId) {
            $rule = $this->references->categorizationRule($command->qualifiedByRuleId, $transaction->getUser());
            $transaction->qualify($rule->getQualification(), $rule);

            return;
        }

        // '' is the historical way to empty the category; clearFields is the explicit one.
        $clearsCategory = '' === $command->categoryId || (null === $command->categoryId && $command->clears('categoryId'));
        $category = match (true) {
            $clearsCategory => null,
            null !== $command->categoryId => $this->references->category($command->categoryId, $transaction->getUser()),
            default => $transaction->getCategory(),
        };

        if (null !== $command->qualification) {
            $transaction->qualify(Qualification::fromArray($command->qualification, $category), null);
        } elseif ($clearsCategory) {
            $transaction->qualify(Qualification::none(), null);
        } elseif (null !== $command->categoryId && null !== $category) {
            $transaction->qualify(Qualification::fromCategory($category), null);
        }
    }

    /**
     * Attributes the spend by hand to a one-off envelope (Hellfest 2026), or
     * takes it back: it keeps its category either way, and only leaves the
     * monthly and annual envelopes while attributed.
     */
    private function applyOneOffEnvelope(Transaction $transaction, UpdateTransactionCommand $command): void
    {
        if (null === $command->oneOffEnvelopeId) {
            if ($command->clears('oneOffEnvelope')) {
                $transaction->setOneOffEnvelope(null);
            }

            return;
        }

        try {
            $envelope = $this->references->envelope($command->oneOffEnvelopeId, $transaction->getUser());
        } catch (\DomainException $e) {
            throw new OneOffAttributionException($e->getMessage(), 0, $e);
        }

        if (!$envelope->isOneOff()) {
            throw new OneOffAttributionException('A spend is attributed by hand to a one-off envelope only: the monthly and annual ones follow its category.');
        }
        // An envelope weighs spends only: an income or a neutral movement would sit there counting for nothing.
        if ($transaction->getAmountCents() >= 0 || TransferKind::None !== $transaction->getTransferKind()) {
            throw new OneOffAttributionException('Only a spend is attributed to a one-off envelope, not an income nor a transfer.');
        }

        $transaction->setOneOffEnvelope($envelope);
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
     * Points the debit leg of a repayment at the advance it gives back — the
     * hard guarantee the owner can give by hand, next to the detection's
     * exact-amount rule.
     */
    private function applyRepays(Transaction $transaction, UpdateTransactionCommand $command): void
    {
        if (null === $command->repaysTransactionId) {
            return;
        }

        if (TransferKind::Repayment !== $transaction->getTransferKind()) {
            throw new \DomainException('repaysTransactionId goes with transferKind repayment.');
        }
        if ($transaction->getAmountCents() >= 0) {
            throw new \DomainException('A repayment points at its advance from the debit leg, the one leaving the checking account.');
        }

        $advance = $this->references->transaction($command->repaysTransactionId, $transaction->getUser(), 'Advance');
        if (TransferKind::Advance !== $advance->getTransferKind() || $advance->getAmountCents() >= 0) {
            throw new \DomainException('repaysTransactionId must name the debit leg of an advance (get_savings_advances lists them).');
        }

        $transaction->setRepays($advance);
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
        if ($kind->isBetweenAccounts() && $sameAccount) {
            throw new \DomainException('An internal transfer goes between two different accounts.');
        }
        // The bank gives a rejected payment back where it took it from.
        if (TransferKind::Rejected === $kind && !$sameAccount) {
            throw new \DomainException('A rejection is credited back on the account of the payment it cancels.');
        }

        $transaction->pairWith($counterpart, $kind, $source);
    }
}
