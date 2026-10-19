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
     * @return array{month: string, step: string, handledTransactionIds: list<string>, updatedAt: string|null}
     */
    public function execute(User $user, \DateTimeImmutable $month): array
    {
        $progress = $this->repository->findOneByUserAndMonth($user, $month);

        return null === $progress
            ? ['month' => $month->format('Y-m'), 'step' => ReviewStep::Review->value, 'handledTransactionIds' => [], 'updatedAt' => null]
            : self::describe($progress);
    }

    /** @return array{month: string, step: string, handledTransactionIds: list<string>, updatedAt: string|null} */
    public static function describe(ReviewProgress $progress): array
    {
        return [
            'month' => $progress->getMonth()->format('Y-m'),
            'step' => $progress->getStep()->value,
            'handledTransactionIds' => $progress->getHandledTransactionIds(),
            'updatedAt' => $progress->getUpdatedAt()?->format(\DATE_ATOM),
        ];
    }
}
