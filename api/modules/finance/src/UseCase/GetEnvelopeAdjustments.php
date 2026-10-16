<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\EnvelopeNesting;
use Maggie\Finance\Service\SeasonalAmounts;
use Symfony\Component\Uid\Ulid;

/**
 * Each monthly envelope of a month read against what was really spent, and —
 * only for the ones that do not fit — what next month's amount should be.
 *
 * An envelope is ill-suited when:
 *  - it was exceeded three months running (the month and the two before it)
 *    and what was spent was mostly worth keeping: the amount goes up to the
 *    kept spending. Mostly superfluous spending is a habit to change, not a
 *    budget to raise: no suggestion;
 *  - it was left under three quarters of its amount three months running:
 *    the amount comes down to the most spent in those months;
 *  - the same month of the past years was far from the amount planned for
 *    next month (a season): the suggestion then names the month it is for,
 *    and wins over the two above.
 *
 * Nothing is written here: a suggestion waits for the owner's gesture.
 *
 * @phpstan-type Suggestion array{proposedCents: int, direction: string, reason: string, seasonal: array{fromMonth: int, toMonth: int}|null}
 */
class GetEnvelopeAdjustments
{
    public const RECENT_MONTHS = 3;
    public const PAST_YEARS = 2;
    /** Under this share of its amount, three months running, an envelope is too big. */
    public const SLACK_RATIO = 0.75;
    /** A past year's month this far from next month's amount makes a season. */
    public const SEASON_GAP_RATIO = 0.2;
    /** Past this share of avoidable spending among the judged, the spending is not what to budget. */
    public const AVOIDABLE_LIMIT = 0.5;
    public const ROUNDING_CENTS = 500;
    /** The day of the month the review is held: the month under way is read as it stands from then on. */
    public const REVIEW_DAY = 25;

    private const MONTH_NAMES = [
        1 => 'janvier', 2 => 'février', 3 => 'mars', 4 => 'avril', 5 => 'mai', 6 => 'juin',
        7 => 'juillet', 8 => 'août', 9 => 'septembre', 10 => 'octobre', 11 => 'novembre', 12 => 'décembre',
    ];

    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly EnvelopeNesting $nesting,
    ) {
    }

    /**
     * @param \DateTimeImmutable $month any day of the month read
     *
     * @return array<string, mixed>
     */
    public function execute(User $user, \DateTimeImmutable $month, ?\DateTimeImmutable $today = null): array
    {
        $month = $month->modify('first day of this month')->setTime(0, 0);
        $target = $month->modify('+1 month');
        $today ??= new \DateTimeImmutable('today');

        $envelopes = $this->envelopeRepository->findForPeriod($user, (int) $month->format('Y'), (int) $month->format('n'));
        $targets = [];
        foreach ($this->envelopeRepository->findForPeriod($user, (int) $target->format('Y'), (int) $target->format('n')) as $envelope) {
            $targets[(string) $envelope->requireCategory()->getId()] = $envelope;
        }

        $lines = [];
        foreach ($envelopes as $envelope) {
            $lines[] = $this->line($user, $envelope, $envelopes, $targets[(string) $envelope->requireCategory()->getId()] ?? null, $month, $target, $today);
        }
        usort($lines, static fn (array $a, array $b): int => $a['categoryPath'] <=> $b['categoryPath']);

        return [
            'month' => $month->format('Y-m'),
            'targetMonth' => $target->format('Y-m'),
            'envelopes' => $lines,
            'suggestionCount' => \count(array_filter($lines, static fn (array $line): bool => null !== $line['suggestion'])),
        ];
    }

    /**
     * @param list<Envelope> $envelopes the envelopes of the month read
     *
     * @return array<string, mixed>
     */
    private function line(User $user, Envelope $envelope, array $envelopes, ?Envelope $targetEnvelope, \DateTimeImmutable $month, \DateTimeImmutable $target, \DateTimeImmutable $today): array
    {
        $category = $envelope->requireCategory();
        $categoryIds = $this->categoryRepository->findSelfAndDescendantIds($category);

        $recent = [];
        for ($back = 0; $back < self::RECENT_MONTHS; ++$back) {
            $recent[] = $this->monthOf($user, $category, $categoryIds, $month->modify(sprintf('-%d months', $back)));
        }
        $actualCents = $recent[0]['consumedCents'];

        // What next month is budgeted at now: its own envelope, else what rolling this one over would give.
        $targetAmountCents = null !== $targetEnvelope
            ? $targetEnvelope->getMonthAmountCents()
            : (SeasonalAmounts::covering($envelope->getSeasonalAmounts(), (int) $target->format('n'))['amountCents'] ?? $envelope->getAmountCents());

        $allocatedCents = 0;
        foreach ($this->nesting->daughtersOf($envelope, $envelopes) as $daughter) {
            $allocatedCents += $daughter->getMonthAmountCents();
        }

        $suggestion = $this->seasonalSuggestion($user, $categoryIds, $target, $targetAmountCents, $allocatedCents)
            ?? $this->recentSuggestion($recent, $month, $today, $targetAmountCents, $allocatedCents);

        return [
            'envelopeId' => (string) $envelope->getId(),
            'categoryId' => (string) $category->getId(),
            'categoryName' => $category->getName(),
            'categoryPath' => $category->getPath(),
            'currency' => $envelope->getCurrency(),
            'amountCents' => $envelope->getMonthAmountCents(),
            'actualCents' => $actualCents,
            'targetEnvelopeId' => null === $targetEnvelope ? null : (string) $targetEnvelope->getId(),
            'targetAmountCents' => $targetAmountCents,
            'suggestion' => $suggestion,
        ];
    }

    /**
     * @param list<Ulid> $categoryIds
     *
     * @return array{budgetCents: int|null, consumedCents: int, keptCents: int, avoidableCents: int}
     */
    private function monthOf(User $user, Category $category, array $categoryIds, \DateTimeImmutable $start): array
    {
        $until = $start->modify('+1 month');
        $byStatus = $this->transactionRepository->sumByStatusForCategoriesBetween($user, $categoryIds, $start, $until);
        $judged = $this->transactionRepository->sumJudgedConsumedForCategoriesBetween($user, $categoryIds, $start, $until);
        $envelope = $this->envelopeRepository->findOneForPeriod($category, BudgetMode::Monthly, (int) $start->format('Y'), (int) $start->format('n'));

        return [
            'budgetCents' => $envelope?->getMonthAmountCents(),
            'consumedCents' => $byStatus[TransactionStatus::Spent->value] + $byStatus[TransactionStatus::Committed->value],
            'keptCents' => $judged['keep'],
            'avoidableCents' => $judged['avoidable'],
        ];
    }

    /**
     * @param list<array{budgetCents: int|null, consumedCents: int, keptCents: int, avoidableCents: int}> $recent the month read, then the ones before
     *
     * @return Suggestion|null
     */
    private function recentSuggestion(array $recent, \DateTimeImmutable $month, \DateTimeImmutable $today, int $targetAmountCents, int $allocatedCents): ?array
    {
        foreach ($recent as $window) {
            if (null === $window['budgetCents']) {
                return null;
            }
        }

        $exceeded = array_filter($recent, static fn (array $m): bool => $m['consumedCents'] > $m['budgetCents']);
        if (self::RECENT_MONTHS === \count($exceeded)) {
            $kept = array_sum(array_column($recent, 'keptCents'));
            $avoidable = array_sum(array_column($recent, 'avoidableCents'));
            if ($kept + $avoidable > 0 && $avoidable / ($kept + $avoidable) > self::AVOIDABLE_LIMIT) {
                return null;
            }

            $perMonth = array_map(static fn (array $m): int => $m['consumedCents'] - $m['avoidableCents'], $recent);
            $proposed = self::roundUp((int) round(array_sum($perMonth) / self::RECENT_MONTHS));
            if ($proposed <= $targetAmountCents) {
                return null;
            }

            $overshoot = (int) round(array_sum(array_map(
                static fn (array $m): float => ($m['consumedCents'] - $m['budgetCents']) / max(1, $m['budgetCents']) * 100,
                $recent,
            )) / self::RECENT_MONTHS);

            return [
                'proposedCents' => $proposed,
                'direction' => 'raise',
                'reason' => sprintf(
                    'Dépassée %d mois sur %d (de %d %% en moyenne)%s : %s dépensés par mois en moyenne.',
                    self::RECENT_MONTHS,
                    self::RECENT_MONTHS,
                    $overshoot,
                    $kept + $avoidable > 0 ? ', dépenses surtout jugées à conserver' : '',
                    self::euros((int) round(array_sum(array_column($recent, 'consumedCents')) / self::RECENT_MONTHS)),
                ),
                'seasonal' => null,
            ];
        }

        // A month still running only counts as lived from the day of the review.
        $settled = $month->modify('+1 month') <= $today
            || ((int) $today->format('j') >= self::REVIEW_DAY && $today->format('Y-m') === $month->format('Y-m'));
        if (!$settled) {
            return null;
        }

        foreach ($recent as $window) {
            if ($window['budgetCents'] <= 0 || $window['consumedCents'] > $window['budgetCents'] * self::SLACK_RATIO) {
                return null;
            }
        }

        $most = max(array_column($recent, 'consumedCents'));
        $proposed = max(self::roundUp($most), $allocatedCents);
        if ($proposed >= $targetAmountCents) {
            return null;
        }

        return [
            'proposedCents' => $proposed,
            'direction' => 'lower',
            'reason' => sprintf(
                'Tenue avec de la marge %d mois sur %d : %s au plus dépensés pour %s prévus.',
                self::RECENT_MONTHS,
                self::RECENT_MONTHS,
                self::euros($most),
                self::euros(max(array_column($recent, 'budgetCents'))),
            ),
            'seasonal' => null,
        ];
    }

    /**
     * The same month of the past years against the amount planned for the next one.
     *
     * @param list<Ulid> $categoryIds
     *
     * @return Suggestion|null
     */
    private function seasonalSuggestion(User $user, array $categoryIds, \DateTimeImmutable $target, int $targetAmountCents, int $allocatedCents): ?array
    {
        $consumed = [];
        $kept = 0;
        $avoidable = 0;
        for ($back = 1; $back <= self::PAST_YEARS; ++$back) {
            $start = $target->modify(sprintf('-%d years', $back));
            $until = $start->modify('+1 month');
            $byStatus = $this->transactionRepository->sumByStatusForCategoriesBetween($user, $categoryIds, $start, $until);
            $total = $byStatus[TransactionStatus::Spent->value] + $byStatus[TransactionStatus::Committed->value];
            if ($total <= 0) {
                continue;
            }

            $judged = $this->transactionRepository->sumJudgedConsumedForCategoriesBetween($user, $categoryIds, $start, $until);
            $consumed[] = $total - $judged['avoidable'];
            $kept += $judged['keep'];
            $avoidable += $judged['avoidable'];
        }

        if ([] === $consumed || ($kept + $avoidable > 0 && $avoidable / ($kept + $avoidable) > self::AVOIDABLE_LIMIT)) {
            return null;
        }

        $average = (int) round(array_sum($consumed) / \count($consumed));
        $proposed = max(self::roundUp($average), $allocatedCents);
        $gap = $targetAmountCents * self::SEASON_GAP_RATIO;
        $direction = match (true) {
            $proposed - $targetAmountCents > $gap => 'raise',
            $targetAmountCents - $proposed > $gap => 'lower',
            default => null,
        };
        if (null === $direction) {
            return null;
        }

        $number = (int) $target->format('n');

        return [
            'proposedCents' => $proposed,
            'direction' => $direction,
            'reason' => sprintf(
                'En %s, %s dépensés en moyenne ces %d dernières années pour %s prévus.',
                self::MONTH_NAMES[$number],
                self::euros($average),
                \count($consumed),
                self::euros($targetAmountCents),
            ),
            'seasonal' => ['fromMonth' => $number, 'toMonth' => $number],
        ];
    }

    private static function roundUp(int $cents): int
    {
        return (int) (ceil($cents / self::ROUNDING_CENTS) * self::ROUNDING_CENTS);
    }

    private static function euros(int $cents): string
    {
        return 0 === $cents % 100
            ? sprintf('%d €', intdiv($cents, 100))
            : sprintf('%s €', number_format($cents / 100, 2, ',', ' '));
    }
}
