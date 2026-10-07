<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\TransactionNatureGuard;
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
        private readonly EntityBroadcaster $broadcaster,
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
            // The counterparty follows the label only while it was read from it:
            // one the bank named stays, whatever the label becomes.
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

        // The middlewares publish and reindex the result only, so the other
        // legs a marking touched have to be broadcast by hand — a stale index
        // would show the badge on one of the two lines and not the other.
        $alsoChanged = [];
        foreach ($this->applyTransfer($transaction, $command) as $other) {
            if (null !== $other && $other !== $transaction) {
                $alsoChanged[(string) $other->getId()] = $other;
            }
        }

        $updated = $this->updateTransaction->execute($transaction);

        foreach ($alsoChanged as $other) {
            $this->broadcaster->broadcast($other);
        }

        return $updated;
    }

    /**
     * A transfer marking, from the detection or from the user's hand.
     *
     * `transferKind` without a `counterpartId` marks a single-legged transfer,
     * which is the normal case when only one of the two accounts is synced.
     * The source defaults to `manual`, as `categorySource` does: the only
     * caller that knows better — the detection — says so.
     *
     * @return array<int, ?Transaction> the other lines the marking changed
     */
    private function applyTransfer(Transaction $transaction, UpdateTransactionCommand $command): array
    {
        if (null === $command->transferKind) {
            if (!$command->clears('transferKind')) {
                return [];
            }

            $former = $transaction->getCounterpart();
            $transaction->releaseInternalTransfer(TransferSource::Manual);

            return [$former];
        }

        $kind = TransferKind::from($command->transferKind);
        $source = TransferSource::from($command->transferSource ?? TransferSource::Manual->value);

        if (TransferKind::None === $kind) {
            $former = $transaction->getCounterpart();
            $transaction->releaseInternalTransfer($source);

            return [$former];
        }

        if (null === $command->counterpartId) {
            // A single leg contradicts whatever pairing was on this line.
            $former = $transaction->getCounterpart();
            $transaction->releaseInternalTransfer($source)->setTransferKind($kind);

            return [$former];
        }

        if ($command->counterpartId === (string) $transaction->getId()) {
            throw new \DomainException('A transaction cannot be its own counterpart.');
        }

        $counterpart = $this->references->transaction($command->counterpartId, $transaction->getUser(), 'Counterpart');

        if ($counterpart->getAccount()->getId()->equals($transaction->getAccount()->getId())) {
            throw new \DomainException('An internal transfer goes between two different accounts.');
        }

        $freed = [$transaction->getCounterpart(), $counterpart->getCounterpart()];
        $transaction->markAsInternalTransfer($counterpart, $source);

        return [...$freed, $counterpart];
    }
}
