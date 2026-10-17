<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Category\RulePattern;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\ApplyOperationCommand;
use Maggie\Finance\Message\CreateOperationCommand;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

/**
 * One answer to one line of the « À trier » pile: a category (and, if the
 * owner says so, a rule that files the merchant's other lines the same way),
 * or « it is a transfer between my own accounts ».
 *
 * Everything goes through the bus, like any other change of a transaction, so
 * each line touched publishes to Mercure and is reindexed. What is written is
 * always the owner's own decision (`manual`).
 */
class SortTransaction
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly OwnedReferenceResolver $references,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @return array{transaction: Transaction, rule: ?RecurringOperation, ruleCreated: bool, alsoSorted: int, remaining: int}
     *
     * @throws \DomainException when the line is not waiting to be sorted, the category is unknown, or no pattern can be drawn from the label
     */
    public function toCategory(User $user, Transaction $transaction, string $categoryId, bool $alwaysForMerchant = false): array
    {
        $this->assertWaiting($transaction);

        if (!Ulid::isValid($categoryId)) {
            throw new \DomainException("Category not found: {$categoryId}");
        }
        $category = $this->references->category($categoryId, $user);

        $pattern = $alwaysForMerchant ? RulePattern::fromLabel($transaction->getLabel()) : null;
        if ('' === $pattern) {
            throw new \DomainException('No merchant can be drawn from this label: pick the category for this line alone.');
        }

        $pileBefore = $this->transactionRepository->countToSortForUser($user);

        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: (string) $transaction->getId(),
            categoryId: (string) $category->getId(),
        ));

        $rule = null;
        $ruleCreated = false;
        if (null !== $pattern) {
            // The rule that claims this line already — if it files it where the owner
            // just did, it only needs applying; asking « always » must not pile up copies.
            $claiming = $this->categorizeTransaction->winner($transaction, $this->operationRepository->findActiveWithoutRecurrenceForUser($user));
            $rule = null !== $claiming && $claiming->getCategory()->getId()->equals($category->getId()) ? $claiming : null;
            $ruleCreated = null === $rule;

            if (null === $rule) {
                $envelope = $this->bus->dispatch(new CreateOperationCommand(
                    userId: (string) $user->getId(),
                    categoryId: (string) $category->getId(),
                    labelPattern: $pattern,
                    matchType: MatchType::Contains->value,
                    // The longer the pattern, the more specific: it is read first — and
                    // before the rule that claimed this line for another heading.
                    priority: max(mb_strlen($pattern), null === $claiming ? 0 : $claiming->getPriority() + 1),
                    applyToExisting: true,
                ));
                /** @var RecurringOperation $rule */
                $rule = $envelope->last(HandledStamp::class)->getResult();
            } else {
                $this->bus->dispatch(new ApplyOperationCommand((string) $rule->getId()));
            }
        }

        $remaining = $this->transactionRepository->countToSortForUser($user);

        return [
            'transaction' => $transaction,
            'rule' => $rule,
            'ruleCreated' => $ruleCreated,
            'alsoSorted' => max(0, $pileBefore - 1 - $remaining),
            'remaining' => $remaining,
        ];
    }

    /**
     * @return array{transaction: Transaction, remaining: int}
     *
     * @throws \DomainException when the line is not waiting to be sorted
     */
    public function toInternalTransfer(User $user, Transaction $transaction, ?string $counterpartId = null): array
    {
        $this->assertWaiting($transaction);

        if (null !== $counterpartId && !Ulid::isValid($counterpartId)) {
            throw new \DomainException("Counterpart not found: {$counterpartId}");
        }

        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: (string) $transaction->getId(),
            transferKind: TransferKind::Internal->value,
            transferSource: TransferSource::Manual->value,
            counterpartId: $counterpartId,
        ));

        return [
            'transaction' => $transaction,
            'remaining' => $this->transactionRepository->countToSortForUser($user),
        ];
    }

    private function assertWaiting(Transaction $transaction): void
    {
        if (null !== $transaction->getCategory() || TransferKind::None !== $transaction->getTransferKind()) {
            throw new \DomainException('This transaction is not waiting to be sorted: it already has a category or is marked as a transfer.');
        }
    }
}
