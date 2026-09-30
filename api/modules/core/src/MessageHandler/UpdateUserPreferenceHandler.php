<?php

namespace Maggie\Core\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Message\UpdateUserPreferenceCommand;
use Maggie\Core\Repository\UserPreferenceRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateUserPreferenceHandler
{
    public function __construct(
        private readonly UserPreferenceRepository $repository,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    public function __invoke(UpdateUserPreferenceCommand $command): UserPreference
    {
        $pref = $this->repository->find($command->userPreferenceId);
        if (null === $pref) {
            throw new \DomainException("UserPreference not found: {$command->userPreferenceId}");
        }

        if (null !== $command->theme) {
            $pref->setTheme($command->theme);
        }
        if (null !== $command->locale) {
            $pref->setLocale($command->locale);
        }
        if (null !== $command->timezone) {
            $pref->setTimezone($command->timezone);
        }
        if (null !== $command->defaultCalendarView) {
            $pref->setDefaultCalendarView($command->defaultCalendarView);
        }
        if (null !== $command->enabledAgendaIds) {
            $pref->setEnabledAgendaIds($command->enabledAgendaIds);
        }
        if (null !== $command->notificationsEnabled) {
            $pref->setNotificationsEnabled($command->notificationsEnabled);
        }

        $pref->setUpdatedAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        return $pref;
    }
}
