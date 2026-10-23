<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\IndependenceBase;

/**
 * The two measured sides of the independence ratio: the rentes (the credits of
 * the categories flagged as such) and the train de vie, over the same window
 * (M9). Shared by the independence counter and the scenarios' date N.
 */
class MeasureIndependenceBaseline
{
    public function __construct(
        private readonly MeasureMonthlyLifestyle $measureMonthlyLifestyle,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * @return array{base: IndependenceBase, byCategory: list<array{categoryId: string, categoryName: string, incomeCents: int}>}
     */
    public function execute(User $user, \DateTimeImmutable $thisMonth): array
    {
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow($thisMonth);

        $lifestyleCents = $this->measureMonthlyLifestyle->execute($user, $thisMonth);
        $byCategory = $this->transactionRepository->sumPassiveIncomeByCategoryBetween($user, $from, $until);

        return [
            'base' => new IndependenceBase(
                intdiv(array_sum(array_column($byCategory, 'incomeCents')), MeasureMonthlyLifestyle::SAMPLE_MONTHS),
                $lifestyleCents,
            ),
            'byCategory' => $byCategory,
        ];
    }
}
