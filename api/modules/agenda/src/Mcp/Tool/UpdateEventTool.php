<?php

namespace Maggie\Agenda\Mcp\Tool;

use Maggie\Agenda\Repository\EventRepository;
use Doctrine\ORM\EntityManagerInterface;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'update_event', description: 'Update an existing calendar event. Only provided fields will be updated. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes.')]
class UpdateEventTool
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(
        string $id,
        ?string $title = null,
        ?string $date = null,
        ?string $time = null,
        ?int $duration = null,
        ?string $description = null,
        ?string $location = null,
    ): string {
        $event = $this->eventRepository->find($id);
        if ($event === null) {
            return json_encode(['error' => "Event not found: {$id}"], JSON_THROW_ON_ERROR);
        }

        if ($title !== null) {
            $event->setSummary($title);
        }
        if ($description !== null) {
            $event->setDescription($description);
        }
        if ($location !== null) {
            $event->setLocation($location);
        }

        if ($date !== null || $time !== null) {
            $tz = new \DateTimeZone($event->getTimeZone());
            $currentDate = $event->getStartAt()->format('Y-m-d');
            $currentTime = $event->getStartAt()->format('H:i');

            $newDate = $date ?? $currentDate;
            $newTime = $time ?? $currentTime;
            $startAt = new \DateTimeImmutable("{$newDate} {$newTime}", $tz);
            $event->setStartAt($startAt);

            if ($duration !== null) {
                $event->setEndAt($startAt->modify("+{$duration} minutes"));
            } else {
                // Keep original duration
                $originalDuration = $event->getStartAt()->diff($event->getEndAt());
                $event->setEndAt($startAt->add($originalDuration));
            }
        } elseif ($duration !== null) {
            $event->setEndAt($event->getStartAt()->modify("+{$duration} minutes"));
        }

        $this->em->flush();

        return json_encode([
            'success' => true,
            'event' => [
                'id' => (string) $event->getId(),
                'summary' => $event->getSummary(),
                'startAt' => $event->getStartAt()->format('c'),
                'endAt' => $event->getEndAt()->format('c'),
            ],
        ], JSON_THROW_ON_ERROR);
    }
}
