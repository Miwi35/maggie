<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Exception\PeriodPlanRejected;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\SeasonalAmounts;
use Symfony\Component\Uid\Ulid;

/**
 * Writes the plan of a period in one gesture (MAG-462): the envelopes and the
 * planned expenses the planning session ended on.
 *
 * Every item is checked, and every row it would change looked up, before the
 * first write; the writes then share a single flush. That flush is one database
 * transaction, so a plan refused on its last line leaves nothing behind, and
 * the projection (Mercure, Elasticsearch) runs once for the whole plan. Do not
 * dispatch Create/UpdateEnvelope here: each flushes on its own.
 *
 * An item is one of
 *  - `annual`: `{categoryId, amountCents, currency?}`, for a period of a year only;
 *  - `monthly`: `{categoryId, amountCents, currency?, seasons?}`, one monthly
 *    envelope on each month of the period, `seasons` being the whole list of
 *    `{fromMonth, toMonth, amountCents}` (none when absent);
 *  - `one_off`: `{name, amountCents, currency?}`;
 *  - `event`: `{categoryId, label, amountCents, month, accountId?}`, a planned
 *    expense, positive here, booked on the 15th of `month` (`YYYY-MM`, inside
 *    the period) on `accountId` or the first checking account.
 *
 * Replaying a plan updates what it created: an envelope is found by category and
 * month (year), a one-off one by name, an event by category, label and month. An
 * event already committed or sent to arbitration is the owner's decision and is
 * left alone.
 */
class ApplyPeriodPlan
{
    public const MAX_ITEMS = 100;
    public const MAX_CENTS = 100_000_000_000;

    private const KINDS = ['annual', 'monthly', 'one_off', 'event'];
    private const EVENT_DAY = 15;

    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly OwnedReferenceResolver $references,
    ) {
    }

    /**
     * @param array<mixed> $items a JSON body: not trusted to be a list
     *
     * @return list<array{index: int, kind: string, created: int, updated: int, ids: list<string>}>
     *
     * @throws PeriodPlanRejected when the plan or one of its items is refused, nothing having been written
     */
    public function execute(User $user, PlanningPeriod $period, array $items): array
    {
        if ([] === $items || !array_is_list($items)) {
            throw new PeriodPlanRejected('items is required: a list of {kind, …}.');
        }
        if (\count($items) > self::MAX_ITEMS) {
            throw new PeriodPlanRejected(\sprintf('A plan holds %d items at most.', self::MAX_ITEMS));
        }

        $checks = [];
        $seen = [];
        foreach ($items as $index => $item) {
            try {
                $checks[$index] = $this->check($user, $period, $item, $seen);
            } catch (\DomainException|\InvalidArgumentException $e) {
                throw new PeriodPlanRejected($e->getMessage(), $index, $e);
            }
        }

        $applied = [];
        foreach ($checks as $index => $check) {
            $applied[] = ['index' => $index, 'kind' => $check['kind']] + $this->write($user, $period, $check);
        }

        $this->em->flush();

        return array_map(static function (array $result): array {
            $result['ids'] = array_map(static fn (Envelope|Transaction $row): string => (string) $row->getId(), $result['rows']);
            unset($result['rows']);

            return $result;
        }, $applied);
    }

    /**
     * Reads an item and what it will change; touches nothing.
     *
     * @param array<string, true> $seen the keys already planned, to refuse a line given twice
     *
     * @return array<string, mixed>
     */
    private function check(User $user, PlanningPeriod $period, mixed $item, array &$seen): array
    {
        if (!\is_array($item)) {
            throw new PeriodPlanRejected('An item must be an object.');
        }

        $kind = $item['kind'] ?? null;
        if (!\is_string($kind) || !\in_array($kind, self::KINDS, true)) {
            throw new PeriodPlanRejected('kind must be one of '.implode(', ', self::KINDS).'.');
        }

        $amountCents = self::amount($item['amountCents'] ?? null, 'event' === $kind);
        $currency = self::currency($item['currency'] ?? 'EUR');

        $check = ['kind' => $kind, 'amountCents' => $amountCents, 'currency' => $currency];

        switch ($kind) {
            case 'annual':
                if (PlanningPeriod::YEAR !== $period->level) {
                    throw new PeriodPlanRejected('An annual envelope belongs to a calendar year: plan a quarter or a semester with monthly envelopes.');
                }
                $category = $this->category($user, $item);
                self::once($seen, "annual:{$category->getId()}", 'Category planned twice: '.$category->getName());

                return $check + [
                    'category' => $category,
                    'existing' => $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Annual, $period->year, null),
                ];

            case 'monthly':
                $category = $this->category($user, $item);
                self::once($seen, "monthly:{$category->getId()}", 'Category planned twice: '.$category->getName());

                $seasons = SeasonalAmounts::normalize($item['seasons'] ?? []);
                $existing = [];
                foreach ($period->monthStarts() as $month) {
                    $existing[] = $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Monthly, (int) $month->format('Y'), (int) $month->format('n'));
                }

                return $check + ['category' => $category, 'seasons' => $seasons, 'existing' => $existing];

            case 'one_off':
                $name = self::text($item['name'] ?? null, 'name', Envelope::NAME_MAX_LENGTH);
                self::once($seen, 'one_off:'.mb_strtolower($name), "One-off envelope planned twice: {$name}");

                return $check + ['name' => $name, 'existing' => $this->oneOff($user, $name)];

            default:
                $category = $this->category($user, $item);
                $label = self::text($item['label'] ?? null, 'label', 255);
                $month = $item['month'] ?? null;
                if (!\is_string($month) || 1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
                    throw new PeriodPlanRejected('An event needs month, as YYYY-MM.');
                }
                $monthStart = new \DateTimeImmutable($month.'-01');
                if (!$period->contains($monthStart)) {
                    throw new PeriodPlanRejected("The month {$month} is outside the period {$period->key()}.");
                }
                self::once($seen, "event:{$category->getId()}:".mb_strtolower($label).":{$month}", "Expense planned twice: {$label} in {$month}");

                return $check + [
                    'category' => $category,
                    'label' => $label,
                    'monthStart' => $monthStart,
                    'account' => $this->account($user, $item['accountId'] ?? null),
                    'existing' => $this->transactionRepository->findPlannedEvent($user, $category, $label, $monthStart),
                ];
        }
    }

    /**
     * Applies a checked item to the unit of work, without flushing.
     *
     * @param array<string, mixed> $check
     *
     * @return array{created: int, updated: int, rows: list<Envelope|Transaction>}
     */
    private function write(User $user, PlanningPeriod $period, array $check): array
    {
        $created = 0;
        $updated = 0;
        $rows = [];

        switch ($check['kind']) {
            case 'annual':
                $envelope = $check['existing'] ?? $this->newEnvelope($user, BudgetMode::Annual);
                $envelope->setCategory($check['category']);
                $envelope->setYear($period->year);
                $envelope->setMonth(null);
                $this->fill($envelope, $check);
                self::tally($check['existing'], $created, $updated);
                $rows[] = $envelope;
                break;

            case 'monthly':
                foreach ($period->monthStarts() as $position => $month) {
                    $envelope = $check['existing'][$position] ?? $this->newEnvelope($user, BudgetMode::Monthly);
                    $envelope->setCategory($check['category']);
                    $envelope->setYear((int) $month->format('Y'));
                    $envelope->setMonth((int) $month->format('n'));
                    $envelope->setSeasonalAmounts($check['seasons']);
                    $this->fill($envelope, $check);
                    self::tally($check['existing'][$position], $created, $updated);
                    $rows[] = $envelope;
                }
                break;

            case 'one_off':
                $envelope = $check['existing'] ?? $this->newEnvelope($user, BudgetMode::OneOff);
                if (null === $check['existing']) {
                    // An event, not a period: no year nor month.
                    $envelope->setName($check['name']);
                    $envelope->setYear(null);
                    $envelope->setMonth(null);
                }
                $this->fill($envelope, $check);
                self::tally($check['existing'], $created, $updated);
                $rows[] = $envelope;
                break;

            default:
                $existing = $check['existing'];
                if (null === $existing) {
                    $transaction = $this->newEvent($user, $check);
                    $this->em->persist($transaction);
                    ++$created;
                } else {
                    $transaction = $existing;
                    if (TransactionStatus::Planned === $transaction->getStatus()) {
                        $transaction->setAmountCents(-$check['amountCents']);
                        $transaction->setCurrency($check['currency']);
                        ++$updated;
                    }
                }
                $rows[] = $transaction;
        }

        return ['created' => $created, 'updated' => $updated, 'rows' => $rows];
    }

    private function newEnvelope(User $user, BudgetMode $mode): Envelope
    {
        $envelope = new Envelope();
        $envelope->setUser($user);
        $envelope->setMode($mode);
        $this->em->persist($envelope);

        return $envelope;
    }

    /** @param array<string, mixed> $check */
    private function fill(Envelope $envelope, array $check): void
    {
        $envelope->setAmountCents($check['amountCents']);
        $envelope->setCurrency($check['currency']);
    }

    /** @param array<string, mixed> $check */
    private function newEvent(User $user, array $check): Transaction
    {
        /** @var Category $category */
        $category = $check['category'];

        $transaction = new Transaction();
        $transaction->setUser($user);
        $transaction->setAccount($check['account']);
        $transaction->setAmountCents(-$check['amountCents']);
        $transaction->setLabel($check['label']);
        $transaction->setCounterpartyName(MerchantExtractor::extract($check['label']));
        $transaction->setBookedAt($check['monthStart']->setDate(
            (int) $check['monthStart']->format('Y'),
            (int) $check['monthStart']->format('n'),
            self::EVENT_DAY,
        ));
        $transaction->setStatus(TransactionStatus::Planned);
        $transaction->setCurrency($check['currency']);
        $transaction->qualify(Qualification::fromCategory($category), null);

        return $transaction;
    }

    /** @param array<mixed> $item */
    private function category(User $user, array $item): Category
    {
        $id = $item['categoryId'] ?? null;
        if (!\is_string($id) || !Ulid::isValid($id)) {
            throw new PeriodPlanRejected('categoryId is required.');
        }

        return $this->references->category($id, $user);
    }

    private function account(User $user, mixed $id): Account
    {
        if (null !== $id) {
            if (!\is_string($id) || !Ulid::isValid($id)) {
                throw new PeriodPlanRejected('accountId must be an account id.');
            }

            return $this->references->account($id, $user);
        }

        foreach ($this->accountRepository->findByUser($user) as $account) {
            if (AccountType::Checking === $account->getType()) {
                return $account;
            }
        }

        throw new PeriodPlanRejected('No checking account to plan an expense on: send accountId.');
    }

    private function oneOff(User $user, string $name): ?Envelope
    {
        foreach ($this->envelopeRepository->findOneOffByUser($user) as $envelope) {
            if (0 === strcasecmp((string) $envelope->getName(), $name)) {
                return $envelope;
            }
        }

        return null;
    }

    private static function tally(?Envelope $existing, int &$created, int &$updated): void
    {
        null === $existing ? ++$created : ++$updated;
    }

    private static function amount(mixed $value, bool $strictlyPositive): int
    {
        if (!\is_int($value)) {
            throw new PeriodPlanRejected('amountCents must be an integer of cents.');
        }
        if ($value < ($strictlyPositive ? 1 : 0) || $value > self::MAX_CENTS) {
            throw new PeriodPlanRejected($strictlyPositive ? 'An expense is a positive amount in cents.' : 'An envelope amount is a positive amount in cents.');
        }

        return $value;
    }

    private static function currency(mixed $value): string
    {
        if (!\is_string($value) || 1 !== preg_match('/^[A-Z]{3}$/', $value)) {
            throw new PeriodPlanRejected('currency must be a 3-letter ISO 4217 code.');
        }

        return $value;
    }

    private static function text(mixed $value, string $field, int $max): string
    {
        $text = \is_string($value) ? trim($value) : '';
        if ('' === $text) {
            throw new PeriodPlanRejected("{$field} is required.");
        }
        if (mb_strlen($text) > $max) {
            throw new PeriodPlanRejected("{$field} is {$max} characters at most.");
        }

        return $text;
    }

    /** @param array<string, true> $seen */
    private static function once(array &$seen, string $key, string $message): void
    {
        if (isset($seen[$key])) {
            throw new PeriodPlanRejected($message);
        }
        $seen[$key] = true;
    }
}
