<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Enum\ReviewStep;
use Maggie\Finance\Repository\ReviewProgressRepository;

/**
 * Where the user stands in the review of a month (MAG-456). Nothing is stored
 * on read: a month never written starts at the review, nothing handled.
 *
 * It also says which review the month is — `month`, or `quarter`, `semester`,
 * `year` when it closes one, with that `period` and the `nextPeriod` to plan
 * (MAG-462) — so the three clients walk the same steps and none recomputes it.
 */
class GetReviewProgress
{
    public function __construct(
        private readonly ReviewProgressRepository $repository,
    ) {
    }

    /**
     * @param \DateTimeImmutable $month the first day of the month reviewed
     *
     * @return array<string, mixed>
     */
    public function execute(User $user, \DateTimeImmutable $month): array
    {
        $progress = $this->repository->findOneByUserAndMonth($user, $month);

        return null === $progress
            ? ['month' => $month->format('Y-m'), 'step' => ReviewStep::Review->value, 'handledTransactionIds' => [], 'updatedAt' => null] + self::level($month)
            : self::describe($progress);
    }

    /** @return array<string, mixed> */
    public static function describe(ReviewProgress $progress): array
    {
        return [
            'month' => $progress->getMonth()->format('Y-m'),
            'step' => $progress->getStep()->value,
            'handledTransactionIds' => $progress->getHandledTransactionIds(),
            'updatedAt' => $progress->getUpdatedAt()?->format(\DATE_ATOM),
        ] + self::level($progress->getMonth());
    }

    /** @return array{level: string, period: array<string, mixed>|null, nextPeriod: array<string, mixed>|null} */
    private static function level(\DateTimeImmutable $month): array
    {
        try {
            $period = PlanningPeriod::closingAt($month);
        } catch (\InvalidArgumentException) {
            // A year no session is about closes no period; the month is read as any other.
            $period = null;
        }

        return [
            'level' => $period->level ?? 'month',
            'period' => $period?->describe(),
            'nextPeriod' => $period?->next()->describe(),
        ];
    }
}
