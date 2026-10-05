<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
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
use Symfony\Component\Uid\Ulid;

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
    /**
     * What the columns behind a transaction will take. Checked here, with
     * everything else, so no validated plan can be refused by the database
     * driver halfway through: that would answer 400 with the first lines
     * already written, which is the one thing the read-then-write order of
     * {@see self::execute()} exists to prevent.
     */
    private const LABEL_MAX_LENGTH = 255;
    private const AMOUNT_MAX_CENTS = 2_147_483_647;

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
     *                               month, status, accountId, currency,
     *                               isExceptional
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
        PlanningYear::assert($year);

        // Everything is read and checked before anything is written. The
        // commands persist and flush one at a time, so a plan rejected on its
        // last line would otherwise leave the first ones behind — and the
        // retry that follows would plan every one of them twice.
        $readEvents = [];
        foreach ($events as $index => $event) {
            $at = "events[{$index}]";
            $readEvents[] = $this->readEvent($user, $this->asFields($event, $at), $accountId, $at);
        }

        $readEnvelopes = [];
        foreach ($envelopes as $index => $envelope) {
            $at = "envelopes[{$index}]";
            $readEnvelopes[] = $this->readEnvelope($user, $this->asFields($envelope, $at), $at);
        }

        $written = array_map(fn (array $event) => $this->planEvent($user, $year, $event), $readEvents);

        $budgeted = [];
        $counts = ['created' => 0, 'updated' => 0, 'unchanged' => 0];
        foreach ($readEnvelopes as $envelope) {
            [$row, $outcome] = $this->budget($user, $year, $envelope);
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
     * @return array{category: Category, account: Account, label: string, amountCents: int, month: int, status: TransactionStatus, currency: string, isExceptional: bool}
     */
    private function readEvent(User $user, array $event, ?string $fallbackAccount, string $at): array
    {
        $category = $this->requireCategory($user, $event, $at);

        $label = trim(\is_string($event['label'] ?? null) ? $event['label'] : '');
        if ('' === $label || mb_strlen($label) > self::LABEL_MAX_LENGTH) {
            throw new \InvalidArgumentException("{$at}.label is required, and at most ".self::LABEL_MAX_LENGTH.' characters long.');
        }

        $amountCents = $event['amountCents'] ?? null;
        if (!\is_int($amountCents) || $amountCents <= 0 || $amountCents > self::AMOUNT_MAX_CENTS) {
            // Every line of a planning session is an expense; asking for a
            // negative number is a trap, and a forgotten sign would budget
            // income as if it were a cost.
            throw new \InvalidArgumentException("{$at}.amountCents must be an integer of cents above zero, and at most ".self::AMOUNT_MAX_CENTS.'.');
        }

        $month = $event['month'] ?? 1;
        if (!\is_int($month) || $month < 1 || $month > 12) {
            throw new \InvalidArgumentException("{$at}.month must be an integer between 1 and 12.");
        }

        $accountId = $event['accountId'] ?? $fallbackAccount;
        if (!\is_string($accountId) || '' === $accountId) {
            throw new \InvalidArgumentException("{$at}.accountId is required, or give an accountId for the whole plan.");
        }

        $account = Ulid::isValid($accountId) ? $this->accountRepository->find($accountId) : null;
        // Unknown, malformed and someone else's are one answer: the session
        // must never let an id reach another user's account.
        if (null === $account || (string) $account->getUser()->getId() !== (string) $user->getId()) {
            throw new \InvalidArgumentException("{$at}: account not found: {$accountId}");
        }

        $isExceptional = $event['isExceptional'] ?? false;
        if (!\is_bool($isExceptional)) {
            throw new \InvalidArgumentException("{$at}.isExceptional must be a boolean.");
        }

        return [
            'category' => $category,
            'account' => $account,
            'label' => $label,
            'amountCents' => $amountCents,
            'month' => $month,
            'status' => $this->requireStatus($event['status'] ?? TransactionStatus::Planned->value, $at),
            'currency' => $this->requireCurrency($event, $account->getCurrency(), $at),
            'isExceptional' => $isExceptional,
        ];
    }

    /**
     * @param array{category: Category, account: Account, label: string, amountCents: int, month: int, status: TransactionStatus, currency: string, isExceptional: bool} $event
     *
     * @return array<string, mixed>
     */
    private function planEvent(User $user, int $year, array $event): array
    {
        $stamped = $this->bus->dispatch(new CreateTransactionCommand(
            userId: (string) $user->getId(),
            accountId: (string) $event['account']->getId(),
            // The session takes what a thing costs; the ledger holds a debit.
            amountCents: -$event['amountCents'],
            label: $event['label'],
            // A planning session knows a month, not a day (shape, decision 6).
            bookedAt: sprintf('%04d-%02d-01', $year, $event['month']),
            status: $event['status']->value,
            currency: $event['currency'],
            isExceptional: $event['isExceptional'],
            categoryId: (string) $event['category']->getId(),
        ));

        /** @var Transaction $transaction */
        $transaction = $stamped->last(HandledStamp::class)->getResult();

        return [
            'id' => (string) $transaction->getId(),
            'categoryId' => (string) $event['category']->getId(),
            'categoryName' => $event['category']->getName(),
            'label' => $transaction->getLabel(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'status' => $transaction->getStatus()->value,
            'isExceptional' => $transaction->isExceptional(),
        ];
    }

    /**
     * @param array<string, mixed> $envelope
     *
     * @return array{category: Category, amountCents: int, currency: string}
     */
    private function readEnvelope(User $user, array $envelope, string $at): array
    {
        $amountCents = $envelope['amountCents'] ?? null;
        if (!\is_int($amountCents) || $amountCents < 0 || $amountCents > self::AMOUNT_MAX_CENTS) {
            throw new \InvalidArgumentException("{$at}.amountCents must be an integer of cents, zero or above, and at most ".self::AMOUNT_MAX_CENTS.'.');
        }

        return [
            'category' => $this->requireCategory($user, $envelope, $at),
            'amountCents' => $amountCents,
            'currency' => $this->requireCurrency($envelope, 'EUR', $at),
        ];
    }

    /**
     * @param array{category: Category, amountCents: int, currency: string} $envelope
     *
     * @return array{0: array<string, mixed>, 1: 'created'|'updated'|'unchanged'}
     */
    private function budget(User $user, int $year, array $envelope): array
    {
        $category = $envelope['category'];
        $amountCents = $envelope['amountCents'];

        // Looked up here rather than at validation time: two lines of one plan
        // may name the same category, and the second must see what the first
        // wrote instead of creating a duplicate the handler would refuse.
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
                currency: $envelope['currency'],
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

        // A name instead of an id is the likeliest mistake with this tool, and
        // Doctrine answers a non-ULID with a conversion error, not with null.
        $category = Ulid::isValid($categoryId) ? $this->categoryRepository->find($categoryId) : null;

        // An unknown category, a malformed id and somebody else's are the same
        // answer: the session must never reach another user's budget.
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

    /**
     * Nothing validates a command on the bus, and the column is three
     * characters wide: an unchecked code reaches the database driver and comes
     * back as a 500 on what is plain bad input.
     *
     * @param array<string, mixed> $input
     */
    private function requireCurrency(array $input, string $fallback, string $at): string
    {
        $currency = $input['currency'] ?? $fallback;

        if (!\is_string($currency) || 1 !== preg_match('/^[A-Z]{3}$/', $currency)) {
            throw new \InvalidArgumentException("{$at}.currency must be a 3-letter ISO 4217 code.");
        }

        return $currency;
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
