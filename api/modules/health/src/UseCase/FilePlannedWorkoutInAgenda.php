<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Repository\WorkoutRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Files a planned session in the user's « Santé » module agenda (MAG-777):
 * one timed event, same title, start and end, `activity = sport`, never
 * recurring and never sent to Google (the module agenda is not synced). A
 * session that is gone or no longer planned loses its event.
 *
 * Writes only what differs: the event moved in the agenda moves the session,
 * whose change brings it back here with nothing left to do — no round trip
 * without end.
 */
class FilePlannedWorkoutInAgenda
{
    public const AGENDA_NAME = 'Santé';
    public const AGENDA_COLOR = '#FF8A80';

    public function __construct(
        private readonly WorkoutRepository $workoutRepository,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @param string|null $eventId the event of a session that is gone, which no longer tells it
     *
     * @return Event|null the session's event, null when it has none (any more)
     */
    public function execute(string $userId, string $workoutId, ?string $eventId = null): ?Event
    {
        $workout = $this->workoutRepository->findOneOfUser($userId, $workoutId);
        if (null !== $workout && $workout->isPlanned()) {
            return $this->file($workout);
        }

        $this->unfile($userId, $workout, $eventId);

        return null;
    }

    private function file(Workout $workout): Event
    {
        $event = $workout->getEvent();
        $new = null === $event;
        if (null === $event) {
            [$agenda] = $this->moduleAgendas->forUser($workout->getUser(), Agenda::MODULE_HEALTH, self::AGENDA_NAME, self::AGENDA_COLOR, adoptLegacy: false);
            $event = new Event();
            $event->setActivity(Event::ACTIVITY_SPORT);
            $event->setTimeZone($agenda->getTimeZone());
            $agenda->addEvent($event);
            $this->em->persist($event);
            $workout->setEvent($event);
        }

        $zone = new \DateTimeZone($event->getTimeZone());
        $start = $workout->getStartedAt()->setTimezone($zone);
        $end = $start->modify(\sprintf('+%d minutes', $workout->getDurationMinutes()));

        $unchanged = !$new
            && !$event->isAllDay()
            && $event->getSummary() === $workout->getTitle()
            && $event->getStartAt()?->getTimestamp() === $start->getTimestamp()
            && $event->getEndAt()?->getTimestamp() === $end->getTimestamp();
        if ($unchanged) {
            return $event;
        }

        $event->setSummary($workout->getTitle());
        $event->scheduleTimed($start, $end);
        $event->setRrule(null);
        $this->em->flush();

        return $event;
    }

    private function unfile(string $userId, ?Workout $workout, ?string $eventId): void
    {
        $event = $workout?->getEvent();
        $unlinked = null !== $event;
        $workout?->setEvent(null);

        if (null === $event && null !== $eventId && Ulid::isValid($eventId)) {
            $event = $this->em->find(Event::class, $eventId);
        }
        // Only a session's own event: never one of another user's, nor an ordinary one.
        if (null !== $event && (Event::ACTIVITY_SPORT !== $event->getActivity() || (string) $event->getAgenda()->getUser()->getId() !== $userId)) {
            $event = null;
        }

        if (null === $event && !$unlinked) {
            return;
        }
        if (null !== $event) {
            $this->em->remove($event);
        }
        $this->em->flush();
    }
}
