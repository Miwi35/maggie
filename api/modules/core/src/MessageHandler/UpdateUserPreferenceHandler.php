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
        if ($pref === null) {
            throw new \DomainException("UserPreference not found: {$command->userPreferenceId}");
        }

        if ($command->theme !== null) {
            $pref->setTheme($command->theme);
        }
        if ($command->locale !== null) {
            $pref->setLocale($command->locale);
        }
        if ($command->timezone !== null) {
            $pref->setTimezone($command->timezone);
        }
        if ($command->defaultCalendarView !== null) {
            $pref->setDefaultCalendarView($command->defaultCalendarView);
        }
        if ($command->enabledAgendaIds !== null) {
            $pref->setEnabledAgendaIds($command->enabledAgendaIds);
        }
        if ($command->notificationsEnabled !== null) {
            $pref->setNotificationsEnabled($command->notificationsEnabled);
        }

        $pref->setUpdatedAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        return $pref;
    }
}
