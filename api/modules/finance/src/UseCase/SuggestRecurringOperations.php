<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Category\MerchantDictionary;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Message\AttachRecurringOperationsCommand;
use Maggie\Finance\Message\CreateRecurringOperationCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurringOperationGuard;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

/**
 * Reads the statement back and proposes the recurring operations it implies:
 * « Netflix comes back on the 12th of every month, 13,49 € ».
 *
 * Nothing is created by `suggest`, and nothing enters the projections without
 * the owner's yes: `accept` is that yes, and only then do the series exist,
 * with the history they recognise already attached.
 *
 * A payee is grouped by its counterparty, else by the merchant read in the
 * label, per account and per direction — a series lives on one account and
 * is an expense or an income, never both.
 */
class SuggestRecurringOperations
{
    public const int DEFAULT_MIN_OCCURRENCES = 3;

    /** A period is read off the gaps between occurrences: two gaps need three occurrences. */
    private const int FLOOR_OCCURRENCES = 3;

    /** Past this, it is a habitual merchant, not a series. */
    private const int MAX_SPREAD_PERCENT = 50;

    /** Median gap, in days, that names each period. */
    private const array PERIOD_RANGES = [
        'weekly' => [5, 9],
        'monthly' => [28, 34],
        'quarterly' => [85, 95],
        'yearly' => [350, 380],
    ];

    /** Loan instalments stay in `Loan`: a second source for the same money would count it twice. */
    private const string LOAN_LABEL = 'REMBOURSEMENT DE PRET';

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly AccountRepository $accountRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * @return list<array{
     *     label: string, counterpartyName: ?string, counterpartyKey: ?string, labelPattern: ?string,
     *     accountId: string, accountName: string, period: string, anchorOn: string, dayRule: string,
     *     referenceAmountCents: int, amountSpreadPercent: int, occurrences: int,
     *     categoryId: ?string, categoryName: ?string, samples: list<string>
     * }>
     */
    public function suggest(User $user, int $minOccurrences = self::DEFAULT_MIN_OCCURRENCES): array
    {
        $minOccurrences = max($minOccurrences, self::FLOOR_OCCURRENCES);

        $covered = [];
        foreach ($this->operationRepository->findByUser($user) as $operation) {
            $key = $operation->getCounterpartyKey()
                ?? MerchantExtractor::key($operation->getLabelPattern() ?? '');
            if ('' !== $key) {
                $covered[$key] = true;
            }
        }

        $loanKey = MerchantExtractor::key(self::LOAN_LABEL);

        /** @var array<string, array{key: string, account: Account, merchant: string, counterpartyName: ?string, hasCounterparty: bool, rows: list<Transaction>}> $groups */
        $groups = [];

        // Oldest first: already free of attached lines, neutral movements and
        // lines awaiting arbitration, and of what the owner detached by hand.
        foreach ($this->transactionRepository->findRecurringCandidates($user) as $transaction) {
            if (TransactionStatus::Planned === $transaction->getStatus() || 0 === $transaction->getAmountCents()) {
                continue;
            }

            $merchant = MerchantExtractor::extract($transaction->getLabel());
            if (null !== $merchant && MerchantExtractor::key($merchant) === $loanKey) {
                continue;
            }

            $counterpartyKey = $transaction->getCounterpartyKey();
            $key = $counterpartyKey ?? (null === $merchant ? '' : MerchantExtractor::key($merchant));
            if ('' === $key || isset($covered[$key])) {
                continue;
            }

            $groupKey = implode('|', [(string) $transaction->getAccount()->getId(), $transaction->getAmountCents() < 0 ? 'debit' : 'credit', $key]);
            $group = $groups[$groupKey] ?? [
                'key' => $key,
                'account' => $transaction->getAccount(),
                'merchant' => $merchant ?? $transaction->getCounterpartyName() ?? $key,
                'counterpartyName' => null,
                'hasCounterparty' => false,
                'rows' => [],
            ];

            // The latest spelling wins: rows come oldest first.
            if (null !== $merchant) {
                $group['merchant'] = $merchant;
            }
            if (null !== $counterpartyKey) {
                $group['hasCounterparty'] = true;
                $group['counterpartyName'] = $transaction->getCounterpartyName();
            }
            $group['rows'][] = $transaction;
            $groups[$groupKey] = $group;
        }

        $categoriesByName = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            $categoriesByName[mb_strtolower($category->getName())] = $category;
        }

        $suggestions = [];
        foreach ($groups as $group) {
            if (\count($group['rows']) < $minOccurrences) {
                continue;
            }

            $suggestion = $this->describe($group, $categoriesByName);
            if (null !== $suggestion) {
                $suggestions[] = $suggestion;
            }
        }

        // What weighs most, first.
        usort($suggestions, static fn (array $a, array $b) => [abs($b['referenceAmountCents']), $a['label']] <=> [abs($a['referenceAmountCents']), $b['label']]);

        return $suggestions;
    }

    /**
     * The owner's yes: creates the series and attaches the history they
     * recognise in one catch-up pass, so each is born with its occurrences.
     * An entry that cannot be created — no category, a category or account
     * that is not the user's, a malformed field, a payee already covered —
     * is ignored, never an error for the others.
     *
     * @param list<array<string, mixed>> $accepted straight from the request: every field is checked here
     *
     * @return array{created: int, labels: list<string>, attached: int, proposed: int}
     */
    public function accept(User $user, array $accepted): array
    {
        $covered = [];
        foreach ($this->operationRepository->findByUser($user) as $operation) {
            $covered[$operation->getCounterpartyKey() ?? MerchantExtractor::key($operation->getLabelPattern() ?? '')] = true;
        }

        $labels = [];
        $createdIds = [];
        foreach ($accepted as $entry) {
            $command = $this->commandFor($user, $entry);
            if (null === $command) {
                continue;
            }

            $key = null !== $command->counterpartyName
                ? MerchantExtractor::key($command->counterpartyName)
                : MerchantExtractor::key($command->labelPattern ?? '');
            if ('' === $key || isset($covered[$key])) {
                continue;
            }

            try {
                $created = $this->bus->dispatch($command)->last(HandledStamp::class)?->getResult();
            } catch (HandlerFailedException $e) {
                if ($e->getPrevious() instanceof \DomainException) {
                    continue;
                }

                throw $e;
            }

            $covered[$key] = true;
            $labels[] = $command->label;
            if ($created instanceof RecurringOperation) {
                $createdIds[(string) $created->getId()] = true;
            }
        }

        if ([] === $labels) {
            return ['created' => 0, 'labels' => [], 'attached' => 0, 'proposed' => 0];
        }

        $pass = $this->bus->dispatch(new AttachRecurringOperationsCommand((string) $user->getId()))
            ->last(HandledStamp::class)?->getResult();
        if (!\is_array($pass)) {
            throw new \RuntimeException('The catch-up pass returned no result.');
        }

        // The pass covers the whole history, other series included: report only the new ones.
        $ours = static fn (array $match): bool => isset($createdIds[(string) ($match['recurringOperationId'] ?? '')])
            || [] !== array_filter($match['candidates'] ?? [], static fn (array $c) => isset($createdIds[(string) ($c['recurringOperationId'] ?? '')]));

        return [
            'created' => \count($labels),
            'labels' => $labels,
            'attached' => \count(array_filter($pass['attachments'], $ours)),
            'proposed' => \count(array_filter($pass['proposals'], $ours)),
        ];
    }

    /**
     * @param array{key: string, account: Account, merchant: string, counterpartyName: ?string, hasCounterparty: bool, rows: list<Transaction>} $group
     * @param array<string, Category>                                                                                                           $categoriesByName
     *
     * @return array<string, mixed>|null
     */
    private function describe(array $group, array $categoriesByName): ?array
    {
        $rows = $group['rows'];

        $gaps = [];
        for ($i = 1, $count = \count($rows); $i < $count; ++$i) {
            $gaps[] = self::daysBetween($rows[$i - 1]->getBookedAt(), $rows[$i]->getBookedAt());
        }

        // The median, not the mean: a skipped month doubles one gap and
        // would turn a monthly series into a quarterly one.
        $period = self::periodOf(self::median($gaps));
        if (null === $period) {
            return null;
        }

        $amounts = array_map(static fn (Transaction $t) => $t->getAmountCents(), $rows);
        $reference = (int) self::median($amounts, true);
        if (0 === $reference) {
            return null;
        }

        $deviation = 0;
        foreach ($amounts as $amount) {
            $deviation = max($deviation, abs($amount - $reference));
        }
        if ($deviation * 100 > self::MAX_SPREAD_PERCENT * abs($reference)) {
            return null;
        }

        $category = $this->categoryFor($rows, $reference, $group['merchant'], $categoriesByName);
        $name = $group['hasCounterparty'] ? $group['counterpartyName'] : null;

        return [
            'label' => $name ?? $group['merchant'],
            'counterpartyName' => $name,
            'counterpartyKey' => $group['hasCounterparty'] ? $group['key'] : null,
            'labelPattern' => $group['hasCounterparty'] ? null : $group['merchant'],
            'accountId' => (string) $group['account']->getId(),
            'accountName' => $group['account']->getName(),
            'period' => $period->value,
            'anchorOn' => $rows[0]->getBookedAt()->format('Y-m-d'),
            'dayRule' => self::dayRuleOf($period, $rows)->value,
            'referenceAmountCents' => $reference,
            'amountSpreadPercent' => intdiv($deviation * 100, abs($reference)),
            'occurrences' => \count($rows),
            'categoryId' => null === $category ? null : (string) $category->getId(),
            'categoryName' => $category?->getName(),
            'samples' => array_map(
                static fn (Transaction $t) => $t->getLabel(),
                \array_slice(array_reverse($rows), 0, 3),
            ),
        ];
    }

    /**
     * The heading the owner already gave this payee, the most used one;
     * else the dictionary's guess, if the owner has that heading.
     *
     * @param list<Transaction>       $rows
     * @param array<string, Category> $categoriesByName
     */
    private function categoryFor(array $rows, int $reference, string $merchant, array $categoriesByName): ?Category
    {
        /** @var array<string, array{category: Category, lines: int}> $tally */
        $tally = [];
        foreach ($rows as $row) {
            $category = $row->getCategory();
            if (null === $category) {
                continue;
            }
            $id = (string) $category->getId();
            $tally[$id] ??= ['category' => $category, 'lines' => 0];
            ++$tally[$id]['lines'];
        }

        $best = null;
        foreach ($tally as $heading) {
            if (null === $best || $heading['lines'] > $best['lines']) {
                $best = $heading;
            }
        }
        if (null !== $best) {
            return $best['category'];
        }

        $guess = MerchantDictionary::categoryFor($merchant, $reference > 0);

        return null === $guess ? null : ($categoriesByName[mb_strtolower($guess)] ?? null);
    }

    /** @param array<string, mixed> $entry */
    private function commandFor(User $user, array $entry): ?CreateRecurringOperationCommand
    {
        $string = static fn (string $field): ?string => \is_string($entry[$field] ?? null) && '' !== trim($entry[$field]) ? trim($entry[$field]) : null;

        $categoryId = $string('categoryId');
        $accountId = $string('accountId');
        $label = $string('label');
        $counterpartyName = $string('counterpartyName');
        $labelPattern = $string('labelPattern');
        $anchorOn = $string('anchorOn');
        $amount = $entry['referenceAmountCents'] ?? null;

        if (null === $categoryId || null === $accountId || null === $label || null === $anchorOn
            || (null === $counterpartyName && null === $labelPattern)
            || !\is_int($amount) || 0 === $amount
            || !Ulid::isValid($categoryId) || !Ulid::isValid($accountId)) {
            return null;
        }

        $category = $this->categoryRepository->find($categoryId);
        $account = $this->accountRepository->find($accountId);
        if (!$category instanceof Category || !$category->getUser()->getId()->equals($user->getId())
            || !$account instanceof Account || !$account->getUser()->getId()->equals($user->getId())) {
            return null;
        }

        $period = RecurrencePeriod::tryFrom($string('period') ?? '');
        $dayRule = DayRule::tryFrom($string('dayRule') ?? DayRule::FixedDay->value);
        if (null === $period || null === $dayRule || (RecurrencePeriod::Weekly === $period && DayRule::LastDayOfMonth === $dayRule)) {
            return null;
        }

        try {
            RecurringOperationGuard::date($anchorOn, 'anchorOn');
        } catch (\DomainException) {
            return null;
        }

        return new CreateRecurringOperationCommand(
            userId: (string) $user->getId(),
            label: mb_substr($label, 0, 255),
            categoryId: $categoryId,
            accountId: $accountId,
            referenceAmountCents: $amount,
            anchorOn: $anchorOn,
            counterpartyName: $counterpartyName,
            labelPattern: null === $counterpartyName ? $labelPattern : null,
            period: $period->value,
            dayRule: $dayRule->value,
        );
    }

    /** @param list<Transaction> $rows */
    private static function dayRuleOf(RecurrencePeriod $period, array $rows): DayRule
    {
        if (RecurrencePeriod::Weekly === $period) {
            return DayRule::FixedDay;
        }

        foreach ($rows as $row) {
            $date = $row->getBookedAt();
            if ((int) $date->format('j') < (int) $date->format('t') - 2) {
                return DayRule::FixedDay;
            }
        }

        return DayRule::LastDayOfMonth;
    }

    private static function periodOf(float $medianGap): ?RecurrencePeriod
    {
        foreach (self::PERIOD_RANGES as $period => [$min, $max]) {
            if ($medianGap >= $min && $medianGap <= $max) {
                return RecurrencePeriod::from($period);
            }
        }

        return null;
    }

    /**
     * @param list<int> $values
     * @param bool      $whole  an even count gives the integer midpoint, truncated, instead of a half
     */
    private static function median(array $values, bool $whole = false): float|int
    {
        sort($values);
        $count = \count($values);
        $middle = intdiv($count, 2);

        if (1 === $count % 2) {
            return $values[$middle];
        }

        return $whole
            ? intdiv($values[$middle - 1] + $values[$middle], 2)
            : ($values[$middle - 1] + $values[$middle]) / 2;
    }

    private static function daysBetween(\DateTimeImmutable $from, \DateTimeImmutable $to): int
    {
        $utc = new \DateTimeZone('UTC');

        return (int) (new \DateTimeImmutable($from->format('Y-m-d'), $utc))
            ->diff(new \DateTimeImmutable($to->format('Y-m-d'), $utc))
            ->format('%r%a');
    }
}
