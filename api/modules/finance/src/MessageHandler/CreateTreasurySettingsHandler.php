<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Message\CreateTreasurySettingsCommand;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateTreasurySettingsHandler
{
    public function __construct(
        private readonly TreasurySettingsRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CreateTreasurySettingsCommand $command): TreasurySettings
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $existing = $this->repository->findOneByUser($user);
        if (null !== $existing) {
            return $existing;
        }

        $settings = (new TreasurySettings())->setUser($user);
        $this->em->persist($settings);
        $this->em->flush();

        return $settings;
    }
}
