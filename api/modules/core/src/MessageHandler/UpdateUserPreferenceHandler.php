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

        // Working hours (MAG-790): checked against what is stored, before anything is written.
        $workStart = $command->workStartTime ?? $pref->getWorkStartTime();
        $workEnd = $command->workEndTime ?? $pref->getWorkEndTime();
        if ((null !== $command->workStartTime || null !== $command->workEndTime) && $workEnd <= $workStart) {
            throw new \DomainException('workEndTime must be after workStartTime');
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
        if (null !== $command->defaultCity) {
            $city = trim($command->defaultCity);
            $pref->setDefaultCity('' === $city ? null : $city);
        }
        if (null !== $command->enabledAgendaIds) {
            $pref->setEnabledAgendaIds($command->enabledAgendaIds);
        }
        if (null !== $command->notificationsEnabled) {
            $pref->setNotificationsEnabled($command->notificationsEnabled);
        }

        if (null !== $command->quietHoursStart || null !== $command->quietHoursEnd) {
            $start = '' === $command->quietHoursStart ? null : $command->quietHoursStart;
            $end = '' === $command->quietHoursEnd ? null : $command->quietHoursEnd;
            $pref->setQuietHoursStart($start)->setQuietHoursEnd($end);
        }

        if (null !== $command->morningDigestEnabled) {
            $pref->setMorningDigestEnabled($command->morningDigestEnabled);
        }
        if (null !== $command->morningDigestTime) {
            $pref->setMorningDigestTime($command->morningDigestTime);
        }
        if (null !== $command->eveningReviewEnabled) {
            $pref->setEveningReviewEnabled($command->eveningReviewEnabled);
        }
        if (null !== $command->eveningReviewTime) {
            $pref->setEveningReviewTime($command->eveningReviewTime);
        }

        if (null !== $command->workStartTime || null !== $command->workEndTime) {
            $pref->setWorkStartTime($workStart)->setWorkEndTime($workEnd);
        }
        if (null !== $command->workDays) {
            $pref->setWorkDays($command->workDays);
        }

        $pref->setUpdatedAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        return $pref;
    }
}
