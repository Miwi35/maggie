<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayReview;
use Maggie\Calendar\Message\CreateDayReviewCommand;
use Maggie\Calendar\Repository\DayReviewRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateDayReviewHandler
{
    public function __construct(
        private readonly DayReviewRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateDayReviewCommand $command): DayReview
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $existing = $this->repository->findOneByUserAndDate($user, $command->date);
        if (null !== $existing) {
            return $existing;
        }

        $review = (new DayReview())->setUser($user)->setDate($command->date);
        $this->em->persist($review);
        $this->em->flush();

        return $review;
    }
}
