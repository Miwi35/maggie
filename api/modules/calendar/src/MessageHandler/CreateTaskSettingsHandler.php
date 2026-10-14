<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\CreateTaskSettingsCommand;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTaskSettingsHandler
{
    public function __construct(
        private readonly TaskSettingsRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateTaskSettingsCommand $command): TaskSettings
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $existing = $this->repository->findOneByUser($user);
        if (null !== $existing) {
            return $existing;
        }

        $settings = (new TaskSettings())->setUser($user);
        $this->em->persist($settings);
        $this->em->flush();

        return $settings;
    }
}
