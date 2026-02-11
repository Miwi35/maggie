<?php

namespace Maggie\Agenda\Mcp\Tool;

use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Repository\CalendarRepository;
use Doctrine\ORM\EntityManagerInterface;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'create_event', description: 'Create a new calendar event. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes (default 60). Returns the created event.')]
class CreateEventTool
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly CalendarRepository $calendarRepository,
    ) {
    }

    public function __invoke(
        string $title,
        string $date,
        string $time = '09:00',
        int $duration = 60,
        ?string $description = null,
        ?string $location = null,
    ): string {
        $calendar = $this->calendarRepository->findDefault();
        if ($calendar === null) {
            return json_encode(['error' => 'No default calendar found'], JSON_THROW_ON_ERROR);
        }

        $tz = new \DateTimeZone($calendar->getTimeZone());
        $startAt = new \DateTimeImmutable("{$date} {$time}", $tz);
        $endAt = $startAt->modify("+{$duration} minutes");

        $event = new Event();
        $event->setSummary($title);
        $event->setStartAt($startAt);
        $event->setEndAt($endAt);
        $event->setTimeZone($calendar->getTimeZone());
        $event->setCalendar($calendar);

        if ($description !== null) {
            $event->setDescription($description);
        }
        if ($location !== null) {
            $event->setLocation($location);
        }

        $this->em->persist($event);
        $this->em->flush();

        return json_encode([
            'success' => true,
            'event' => [
                'id' => (string) $event->getId(),
                'summary' => $event->getSummary(),
                'startAt' => $event->getStartAt()->format('c'),
                'endAt' => $event->getEndAt()->format('c'),
                'calendar' => $calendar->getName(),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
