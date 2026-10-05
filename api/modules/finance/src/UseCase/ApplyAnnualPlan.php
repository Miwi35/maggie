<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The yearly planning session, write side: the events the owner validated
 * become planned transactions, and the amounts they add up to become the
 * annual envelopes of the year ahead.
 *
 * Unlike {@see RollOverEnvelopes}, which exists so a budget does not have to
 * be retyped and therefore never overwrites, this one writes a decision: the
 * amount it was shown is the amount it sets.
 */
class ApplyAnnualPlan
{
    /** You do not plan what has already left the account. */
    private const PLANNABLE = [
        TransactionStatus::Planned,
        TransactionStatus::Committed,
        TransactionStatus::ToArbitrate,
    ];

    public function __construct(
        private readonly AccountRepository $accountRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @param list<mixed> $events    each an array with categoryId, label,
     *                               amountCents (positive), and optionally
     *                               month, status, accountId, currency
     * @param list<mixed> $envelopes each an array with categoryId and
     *                               amountCents
     * @param string|null $accountId the account the events fall on, unless one
     *                               names its own
     *
     * @return array<string, mixed>
     *
     * @throws \InvalidArgumentException on anything the session cannot write
     */
    public function execute(
        User $user,
        int $year,
        array $events,
        array $envelopes,
        ?string $accountId = null,
    ): array {
        $written = [];
        foreach ($events as $index => $event) {
            $at = "events[{$index}]";
            $written[] = $this->planEvent($user, $year, $this->asFields($event, $at), $accountId, $at);
        }

        $budgeted = [];
        $counts = ['created' => 0, 'updated' => 0, 'unchanged' => 0];
        foreach ($envelopes as $index => $envelope) {
            $at = "envelopes[{$index}]";
            [$row, $outcome] = $this->budget($user, $year, $this->asFields($envelope, $at), $at);
            ++$counts[$outcome];
            $budgeted[] = $row + ['outcome' => $outcome];
        }

        return [
            'year' => $year,
            'eventsCreated' => \count($written),
            'envelopesCreated' => $counts['created'],
            'envelopesUpdated' => $counts['updated'],
            'envelopesUnchanged' => $counts['unchanged'],
            'events' => $written,
            'envelopes' => $budgeted,
        ];
    }

    /**
     * @param array<string, mixed> $event
     *
     * @return array<string, mixed>
     */
    private function planEvent(User $user, int $year, array $event, ?string $fallbackAccount, string $at): array
    {
        $category = $this->requireCategory($user, $event, $at);

        $label = trim(\is_string($event['label'] ?? null) ? $event['label'] : '');
        if ('' === $label) {
            throw new \InvalidArgumentException("{$at}.label is required.");
        }

        $amountCents = $event['amountCents'] ?? null;
        if (!\is_int($amountCents) || $amountCents <= 0) {
            // Every line of a planning session is an expense; asking for a
            // negative number is a trap, and a forgotten sign would budget
            // income as if it were a cost.
            throw new \InvalidArgumentException("{$at}.amountCents must be a positive integer of cents.");
        }

        $month = $event['month'] ?? 1;
        if (!\is_int($month) || $month < 1 || $month > 12) {
            throw new \InvalidArgumentException("{$at}.month must be an integer between 1 and 12.");
        }

        $status = $this->requireStatus($event['status'] ?? TransactionStatus::Planned->value, $at);

        $accountId = $event['accountId'] ?? $fallbackAccount;
        if (!\is_string($accountId) || '' === $accountId) {
            throw new \InvalidArgumentException("{$at}.accountId is required, or give an accountId for the whole plan.");
        }

        $account = $this->accountRepository->find($accountId);
        if (null === $account || (string) $account->getUser()->getId() !== (string) $user->getId()) {
            throw new \InvalidArgumentException("{$at}: account not found: {$accountId}");
        }

        $currency = $event['currency'] ?? $account->getCurrency();
        if (!\is_string($currency)) {
            throw new \InvalidArgumentException("{$at}.currency must be a 3-letter ISO 4217 code.");
        }

        $stamped = $this->bus->dispatch(new CreateTransactionCommand(
            userId: (string) $user->getId(),
            accountId: (string) $account->getId(),
            // The session takes what a thing costs; the ledger holds a debit.
            amountCents: -$amountCents,
            label: $label,
            // A planning session knows a month, not a day (shape, decision 6).
            bookedAt: sprintf('%04d-%02d-01', $year, $month),
            status: $status->value,
            currency: $currency,
            categoryId: (string) $category->getId(),
        ));

        /** @var Transaction $transaction */
        $transaction = $stamped->last(HandledStamp::class)->getResult();

        return [
            'id' => (string) $transaction->getId(),
            'categoryId' => (string) $category->getId(),
            'categoryName' => $category->getName(),
            'label' => $transaction->getLabel(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'status' => $transaction->getStatus()->value,
        ];
    }

    /**
     * @param array<string, mixed> $envelope
     *
     * @return array{0: array<string, mixed>, 1: 'created'|'updated'|'unchanged'}
     */
    private function budget(User $user, int $year, array $envelope, string $at): array
    {
        $category = $this->requireCategory($user, $envelope, $at);

        $amountCents = $envelope['amountCents'] ?? null;
        if (!\is_int($amountCents) || $amountCents < 0) {
            throw new \InvalidArgumentException("{$at}.amountCents must be a positive integer of cents.");
        }

        $existing = $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Annual, $year, null);

        if (null !== $existing && $existing->getAmountCents() === $amountCents) {
            return [$this->serialize($existing, $category), 'unchanged'];
        }

        $command = null === $existing
            ? new CreateEnvelopeCommand(
                userId: (string) $user->getId(),
                categoryId: (string) $category->getId(),
                amountCents: $amountCents,
                year: $year,
                mode: BudgetMode::Annual->value,
                month: null,
                currency: \is_string($envelope['currency'] ?? null) ? $envelope['currency'] : 'EUR',
            )
            : new UpdateEnvelopeCommand(
                envelopeId: (string) $existing->getId(),
                amountCents: $amountCents,
            );

        /** @var Envelope $written */
        $written = $this->bus->dispatch($command)->last(HandledStamp::class)->getResult();

        return [$this->serialize($written, $category), null === $existing ? 'created' : 'updated'];
    }

    /** @return array<string, mixed> */
    private function asFields(mixed $entry, string $at): array
    {
        if (!\is_array($entry)) {
            throw new \InvalidArgumentException("{$at} must be an object.");
        }

        return $entry;
    }

    /** @param array<string, mixed> $input */
    private function requireCategory(User $user, array $input, string $at): Category
    {
        $categoryId = $input['categoryId'] ?? null;
        if (!\is_string($categoryId) || '' === $categoryId) {
            throw new \InvalidArgumentException("{$at}.categoryId is required.");
        }

        $category = $this->categoryRepository->find($categoryId);
        // An unknown category and someone else's are the same answer here:
        // the session must never let an id reach another user's budget.
        if (null === $category || (string) $category->getUser()->getId() !== (string) $user->getId()) {
            throw new \InvalidArgumentException("{$at}: category not found: {$categoryId}");
        }

        return $category;
    }

    private function requireStatus(mixed $status, string $at): TransactionStatus
    {
        $parsed = \is_string($status) ? TransactionStatus::tryFrom($status) : null;

        if (null === $parsed || !\in_array($parsed, self::PLANNABLE, true)) {
            $allowed = implode(', ', array_map(fn (TransactionStatus $s) => $s->value, self::PLANNABLE));

            throw new \InvalidArgumentException("{$at}.status must be one of: {$allowed}.");
        }

        return $parsed;
    }

    /** @return array<string, mixed> */
    private function serialize(Envelope $envelope, Category $category): array
    {
        return [
            'id' => (string) $envelope->getId(),
            'categoryId' => (string) $category->getId(),
            'categoryName' => $category->getName(),
            'mode' => $envelope->getMode()->value,
            'amountCents' => $envelope->getAmountCents(),
            'currency' => $envelope->getCurrency(),
            'year' => $envelope->getYear(),
        ];
    }
}
