<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The salary the month is judged on: a stable mark, not what came in.
 *
 * The salary is the recurring income series whose category is the standard
 * « Salaire » — by its key, so a rename changes nothing. Its mark is the
 * series' expected amount, brought to a month; two employers make two series,
 * added. A series whose amount is measured is read on its last twelve
 * payments, so two salaries paid the same day still average one salary.
 *
 * What the owner declared on the safety cushion wins. Bonuses, rentes,
 * exceptional credits and neutral movements never enter: they are not in the
 * salary category, or not in its payments. Nothing at all is `unknown`, never
 * a mark of 0 €.
 *
 * A pure read. Amounts are positive cents, summed across accounts without
 * converting currencies, like the recurring operations' totals.
 */
class GetReferenceSalary
{
    public const string DECLARED = 'declared';
    public const string EXPECTED = 'expected';
    public const string MEASURED = 'measured';
    public const string UNKNOWN = 'unknown';

    /** How many payments a measured series is averaged on. */
    public const int MEASURE_WINDOW = 12;

    public function __construct(
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly SafetyCushionRepository $cushionRepository,
    ) {
    }

    /**
     * @return array{
     *     monthlyCents: int|null,
     *     source: string,
     *     isConfigured: bool,
     *     declaredMonthlyCents: int|null,
     *     operations: list<array{id: string, label: string, categoryName: string, period: string, referenceAmountCents: int, monthlyCents: int, source: string, paymentCount: int}>
     * }
     */
    public function execute(User $user, ?\DateTimeImmutable $today = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);

        $operations = [];
        foreach ($this->operationRepository->findByUser($user) as $operation) {
            if (self::isSalary($operation, $today)) {
                $operations[] = $this->describe($operation);
            }
        }

        $declared = $this->cushionRepository->findOneByUser($user)?->getMonthlyNetIncomeCents() ?? 0;
        $declared = $declared > 0 ? $declared : null;

        [$monthly, $source] = match (true) {
            null !== $declared => [$declared, self::DECLARED],
            [] === $operations => [null, self::UNKNOWN],
            default => [
                array_sum(array_column($operations, 'monthlyCents')),
                \in_array(self::MEASURED, array_column($operations, 'source'), true) ? self::MEASURED : self::EXPECTED,
            ],
        };

        return [
            'monthlyCents' => $monthly,
            'source' => $source,
            'isConfigured' => null !== $monthly,
            'declaredMonthlyCents' => $declared,
            'operations' => $operations,
        ];
    }

    /**
     * An income series running today, in the salary category or one under it.
     * A rente is never a salary, whatever the category says.
     */
    private static function isSalary(RecurringOperation $operation, \DateTimeImmutable $today): bool
    {
        $category = $operation->getCategory();

        return $operation->getReferenceAmountCents() > 0
            && $operation->isActiveOn($today)
            && $category->isStandard(StandardCategories::SALARY)
            && !$category->isPassiveIncome();
    }

    /**
     * What one series brings in a month. A measured series is read on its
     * payments; one with none yet falls back on its reference, the only figure
     * there is, and says so with `expected`.
     *
     * @return array{id: string, label: string, categoryName: string, period: string, referenceAmountCents: int, monthlyCents: int, source: string, paymentCount: int}
     */
    private function describe(RecurringOperation $operation): array
    {
        $amount = $operation->getReferenceAmountCents();
        $source = self::EXPECTED;
        $payments = [];

        if (ReferenceAmountSource::Measured === $operation->getReferenceSource()) {
            $payments = $this->transactionRepository->findLastPaidAmounts($operation, self::MEASURE_WINDOW);
            if ([] !== $payments) {
                $amount = intdiv(array_sum($payments), \count($payments));
                $source = self::MEASURED;
            }
        }

        return [
            'id' => (string) $operation->getId(),
            'label' => $operation->getLabel(),
            'categoryName' => $operation->getCategory()->getName(),
            'period' => $operation->getPeriod()->value,
            'referenceAmountCents' => $operation->getReferenceAmountCents(),
            'monthlyCents' => intdiv($amount * $operation->getPeriod()->perYear(), 12),
            'source' => $source,
            'paymentCount' => \count($payments),
        ];
    }
}
