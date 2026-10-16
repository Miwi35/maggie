<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\EnvelopeNesting;
use Maggie\Finance\Service\SeasonalAmounts;

/**
 * What is coming: the week after this one and the month after this one, read
 * from what is entered — the recurring series' occurrences nobody settled yet
 * and the planned and committed movements — set against the envelopes of the
 * next month as they stand once adjusted (MAG-461).
 *
 * Nothing is extrapolated and nothing is suggested here: the envelope amounts
 * are the ones written for the next month (its own envelope, else what
 * rolling the month under way over would give), so a suggestion nobody
 * applied weighs nothing. A change to an envelope changes the projected
 * remainder on the next read.
 *
 * The expected spending of the month is, per top-level envelope, the greater
 * of its amount and what is already committed in its categories (a recurring
 * rent inside « Logement » is part of the envelope, not on top of it), plus
 * what is committed outside every envelope. The remainder is the expected
 * income less that spending: what is left to put toward the projects. Annual
 * and one-off envelopes are not part of a month.
 *
 * A pure read.
 *
 * @phpstan-type Item array{date: string, label: string, amountCents: int, source: string, categoryId: string|null, categoryName: string|null}
 * @phpstan-type Window array{from: string, until: string, incomeCents: int, expenseCents: int, netCents: int, incomes: list<Item>, expenses: list<Item>, days: list<array{date: string, incomeCents: int, expenseCents: int}>}
 */
class GetUpcomingProjection
{
    public function __construct(
        private readonly GetRecurringOccurrences $getRecurringOccurrences,
        private readonly TransactionRepository $transactionRepository,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly EnvelopeNesting $nesting,
    ) {
    }

    /**
     * @return array{today: string, week: Window, month: array<string, mixed>}
     */
    public function execute(User $user, ?\DateTimeImmutable $today = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);

        $weekFrom = $today->modify('monday next week');
        $monthFrom = $today->modify('first day of next month');

        $week = $this->window($user, $weekFrom, $weekFrom->modify('+7 days'));
        $month = $this->window($user, $monthFrom, $monthFrom->modify('+1 month'));

        return [
            'today' => $today->format('Y-m-d'),
            'week' => $week,
            'month' => $month + $this->envelopes($user, $monthFrom, $month),
        ];
    }

    /**
     * @return Window
     */
    private function window(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $items = [];
        foreach ($this->getRecurringOccurrences->execute($user, $from, $until) as $occurrence) {
            $items[] = [
                'date' => $occurrence['date'],
                'label' => $occurrence['label'],
                'amountCents' => $occurrence['amountCents'],
                'source' => 'recurring',
                'categoryId' => $occurrence['categoryId'],
                'categoryName' => $occurrence['categoryName'],
            ];
        }

        foreach ($this->transactionRepository->findForecastMovementsBetween($user, $from, $until) as $movement) {
            if ($movement->isInternalTransfer()) {
                continue;
            }

            $category = $movement->getCategory();
            $items[] = [
                'date' => $movement->getBookedAt()->format('Y-m-d'),
                'label' => $movement->getLabel(),
                'amountCents' => $movement->getAmountCents(),
                'source' => $movement->getStatus()->value,
                'categoryId' => null === $category ? null : (string) $category->getId(),
                'categoryName' => $category?->getName(),
            ];
        }

        usort($items, static fn (array $a, array $b): int => [$a['date'], $a['label']] <=> [$b['date'], $b['label']]);

        $days = [];
        for ($date = $from; $date < $until; $date = $date->modify('+1 day')) {
            $days[$date->format('Y-m-d')] = ['date' => $date->format('Y-m-d'), 'incomeCents' => 0, 'expenseCents' => 0];
        }

        $incomes = [];
        $expenses = [];
        foreach ($items as $item) {
            if ($item['amountCents'] > 0) {
                $incomes[] = $item;
                $days[$item['date']]['incomeCents'] += $item['amountCents'];
            } elseif ($item['amountCents'] < 0) {
                $expenses[] = $item;
                $days[$item['date']]['expenseCents'] += -$item['amountCents'];
            }
        }

        $income = array_sum(array_column($incomes, 'amountCents'));
        $expense = -array_sum(array_column($expenses, 'amountCents'));

        return [
            'from' => $from->format('Y-m-d'),
            'until' => $until->format('Y-m-d'),
            'incomeCents' => $income,
            'expenseCents' => $expense,
            'netCents' => $income - $expense,
            'incomes' => $incomes,
            'expenses' => $expenses,
            'days' => array_values($days),
        ];
    }

    /**
     * @param Window $window
     *
     * @return array{month: string, envelopes: list<array{envelopeId: string|null, categoryId: string, categoryName: string, categoryPath: string, amountCents: int, committedCents: int, projectedCents: int, isOvercommitted: bool}>, envelopesCents: int, unbudgetedCents: int, projectedExpenseCents: int, remainingCents: int}
     */
    private function envelopes(User $user, \DateTimeImmutable $monthFrom, array $window): array
    {
        $year = (int) $monthFrom->format('Y');
        $number = (int) $monthFrom->format('n');

        /** @var array<string, array{envelope: Envelope, amountCents: int, isStored: bool}> $byCategory */
        $byCategory = [];
        foreach ($this->envelopeRepository->findForPeriod($user, $year, $number) as $envelope) {
            $byCategory[(string) $envelope->requireCategory()->getId()] = [
                'envelope' => $envelope,
                'amountCents' => $envelope->getMonthAmountCents(),
                'isStored' => true,
            ];
        }

        $previous = $monthFrom->modify('-1 month');
        foreach ($this->envelopeRepository->findForPeriod($user, (int) $previous->format('Y'), (int) $previous->format('n')) as $envelope) {
            $key = (string) $envelope->requireCategory()->getId();
            if (isset($byCategory[$key])) {
                continue;
            }

            $byCategory[$key] = [
                'envelope' => $envelope,
                'amountCents' => SeasonalAmounts::covering($envelope->getSeasonalAmounts(), $number)['amountCents'] ?? $envelope->getAmountCents(),
                'isStored' => false,
            ];
        }

        $nested = $this->nesting->byCategory(array_column($byCategory, 'envelope'));
        $topLevel = array_filter(
            $byCategory,
            fn (array $line): bool => null === $this->nesting->motherOf($line['envelope'], $nested),
        );

        $committed = [];
        $categoriesOf = [];
        foreach ($topLevel as $key => $line) {
            $categoriesOf[$key] = array_map('strval', $this->categoryRepository->findSelfAndDescendantIds($line['envelope']->requireCategory()));
            $committed[$key] = 0;
        }

        $unbudgeted = 0;
        foreach ($window['expenses'] as $expense) {
            $owner = null;
            foreach ($categoriesOf as $key => $ids) {
                if (null !== $expense['categoryId'] && \in_array($expense['categoryId'], $ids, true)) {
                    $owner = $key;
                    break;
                }
            }

            if (null === $owner) {
                $unbudgeted += -$expense['amountCents'];
            } else {
                $committed[$owner] += -$expense['amountCents'];
            }
        }

        $lines = [];
        $envelopesCents = 0;
        foreach ($topLevel as $key => $line) {
            $category = $line['envelope']->requireCategory();
            $projected = max($line['amountCents'], $committed[$key]);
            $envelopesCents += $projected;
            $lines[] = [
                'envelopeId' => $line['isStored'] ? (string) $line['envelope']->getId() : null,
                'categoryId' => $key,
                'categoryName' => $category->getName(),
                'categoryPath' => $category->getPath(),
                'amountCents' => $line['amountCents'],
                'committedCents' => $committed[$key],
                'projectedCents' => $projected,
                'isOvercommitted' => $committed[$key] > $line['amountCents'],
            ];
        }
        usort($lines, static fn (array $a, array $b): int => $a['categoryPath'] <=> $b['categoryPath']);

        $projectedExpense = $envelopesCents + $unbudgeted;

        return [
            'month' => $monthFrom->format('Y-m'),
            'envelopes' => $lines,
            'envelopesCents' => $envelopesCents,
            'unbudgetedCents' => $unbudgeted,
            'projectedExpenseCents' => $projectedExpense,
            'remainingCents' => $window['incomeCents'] - $projectedExpense,
        ];
    }
}
