<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayReview;
use Maggie\Calendar\Message\SetDayReviewCommand;
use Maggie\Calendar\Repository\DayReviewRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Refusals are {@see \DomainException}s, the caller's to read; a refused list changes nothing. */
#[AsMessageHandler]
class SetDayReviewHandler
{
    public function __construct(
        private readonly DayReviewRepository $repository,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(SetDayReviewCommand $command): DayReview
    {
        $review = $this->repository->findOneBy(['id' => $command->dayReviewId, 'user' => $command->userId])
            ?? throw new \DomainException("Day review not found: {$command->dayReviewId}");

        foreach ($command->helped as $value) {
            if (!\is_string($value)) {
                throw new \DomainException(\sprintf('helped: each value is a string among %s.', implode(', ', DayReview::HELPED)));
            }
        }

        try {
            /** @var list<string> $helped */
            $helped = $command->helped;
            $review->setHelped($helped);
        } catch (\InvalidArgumentException $e) {
            throw new \DomainException($e->getMessage(), 0, $e);
        }
        // The first « Bonne nuit » closes the review; a later change keeps its hour.
        if (null === $review->getReviewedAt()) {
            $review->setReviewedAt($this->clock->now());
        }

        $this->em->flush();

        return $review;
    }
}
