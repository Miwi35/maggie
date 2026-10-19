<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Enum\ReviewStep;
use Maggie\Finance\Repository\ReviewProgressRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Psr\Clock\ClockInterface;

/**
 * Moves the review of a month on (MAG-456): the step replaces the one stored,
 * the handled transactions join those already there. The row is laid down by
 * the first write. Refusals are {@see \DomainException}s and change nothing.
 */
class SetReviewProgress
{
    public function __construct(
        private readonly ReviewProgressRepository $repository,
        private readonly TransactionRepository $transactionRepository,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @param list<string> $handledTransactionIds */
    public function execute(User $user, \DateTimeImmutable $month, ?string $step, array $handledTransactionIds): ReviewProgress
    {
        $reviewStep = null;
        if (null !== $step) {
            $reviewStep = ReviewStep::tryFrom($step)
                ?? throw new \DomainException(\sprintf('step must be one of %s.', implode(', ', ReviewStep::values())));
        }

        $ids = array_values(array_unique($handledTransactionIds));
        if ([] !== $ids) {
            $owned = array_map(
                static fn ($transaction): string => (string) $transaction->getId(),
                $this->transactionRepository->findByIdsForUser($user, $ids),
            );
            $unknown = array_values(array_diff($ids, $owned));
            if ([] !== $unknown) {
                throw new \DomainException(\sprintf('Transaction not found: %s.', implode(', ', $unknown)));
            }
        }

        $progress = $this->repository->findOneByUserAndMonth($user, $month);
        if (null === $progress) {
            $progress = (new ReviewProgress())->setUser($user)->setMonth($month);
            $this->em->persist($progress);
        }

        if (null !== $reviewStep) {
            $progress->setStep($reviewStep);
        }
        $progress->addHandledTransactionIds($ids);
        $progress->setUpdatedAt($this->clock->now());

        $this->em->flush();

        return $progress;
    }
}
