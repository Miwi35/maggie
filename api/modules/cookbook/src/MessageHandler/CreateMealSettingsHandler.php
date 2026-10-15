<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Message\CreateMealSettingsCommand;
use Maggie\Cookbook\Repository\MealSettingsRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateMealSettingsHandler
{
    public function __construct(
        private readonly MealSettingsRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateMealSettingsCommand $command): MealSettings
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $existing = $this->repository->findOneByUser($user);
        if (null !== $existing) {
            return $existing;
        }

        $settings = (new MealSettings())->setUser($user);
        $this->em->persist($settings);
        $this->em->flush();

        return $settings;
    }
}
