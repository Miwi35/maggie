<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Message\CreateDayPlanCommand;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateDayPlanHandler
{
    public function __construct(
        private readonly DayPlanRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateDayPlanCommand $command): DayPlan
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $existing = $this->repository->findOneByUserAndDate($user, $command->date);
        if (null !== $existing) {
            return $existing;
        }

        $plan = (new DayPlan())->setUser($user)->setDate($command->date);
        $this->em->persist($plan);
        $this->em->flush();

        return $plan;
    }
}
