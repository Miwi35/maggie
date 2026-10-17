<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\TransactionMatcher;
use Symfony\Component\Uid\Ulid;

/**
 * What a series would recognise, before it is saved: the transactions the
 * shared engine claims for its counterparty, label pattern, sign and amount
 * tolerance. The date window is not part of it — it only decides, line by
 * line, whether a recognised transaction is attached. Nothing is written.
 */
class PreviewRecurringOperation
{
    public const MAX_MATCHES = 50;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly TransactionMatcher $matcher,
    ) {
    }

    /**
     * @param ?string $accountId narrows the lines to one account, like a series is on one
     *
     * @return array{total: int, matches: list<array{
     *     transactionId: string, label: string, amountCents: int, bookedAt: string, accountId: string
     * }>}
     *
     * @throws \DomainException when the criteria are not those of a valid series
     */
    public function execute(
        User $user,
        int $referenceAmountCents,
        ?string $counterpartyName = null,
        ?string $labelPattern = null,
        int $amountTolerancePercent = RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
        ?string $accountId = null,
    ): array {
        if (0 === $referenceAmountCents) {
            throw new \DomainException('The reference amount cannot be zero: negative for an expense, positive for an income.');
        }
        if ($amountTolerancePercent < 0 || $amountTolerancePercent > 100) {
            throw new \DomainException('The amount tolerance is a percentage between 0 and 100.');
        }
        if (null !== $accountId && !Ulid::isValid($accountId)) {
            throw new \DomainException("Account not found: {$accountId}");
        }

        // A period makes it a series: recognised the way a series is.
        $draft = (new RecurringOperation())
            ->setPeriod(RecurrencePeriod::Monthly)
            ->setCounterpartyName($counterpartyName)
            ->setLabelPattern($labelPattern)
            ->setReferenceAmountCents($referenceAmountCents)
            ->setAmountTolerancePercent($amountTolerancePercent);
        $criteria = $draft->matchCriteria();

        $matches = [];
        $total = 0;
        foreach ($this->transactionRepository->findByUser($user) as $transaction) {
            if (TransferKind::None !== $transaction->getTransferKind()
                || (null !== $accountId && (string) $transaction->getAccount()->getId() !== $accountId)
                || !$this->matcher->matches($criteria, $transaction)
                || !$draft->acceptsAmount($transaction->getAmountCents())) {
                continue;
            }

            ++$total;
            if (\count($matches) < self::MAX_MATCHES) {
                $matches[] = [
                    'transactionId' => (string) $transaction->getId(),
                    'label' => $transaction->getLabel(),
                    'amountCents' => $transaction->getAmountCents(),
                    'bookedAt' => $transaction->getBookedAt()->format(\DateTimeInterface::ATOM),
                    'accountId' => (string) $transaction->getAccount()->getId(),
                ];
            }
        }

        return ['total' => $total, 'matches' => $matches];
    }
}
