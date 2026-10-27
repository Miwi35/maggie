<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Service\TripDay;

/** A trip as `set_trip` and `list_trips` return it (MAG-654), times in Paris. */
final class TripJson
{
    /** @return array<string, mixed> */
    public static function of(Trip $trip): array
    {
        $paris = new \DateTimeZone(TripDay::TIME_ZONE);
        $event = $trip->getEvent();

        $json = [
            'id' => (string) $trip->getId(),
            'eventId' => (string) $event->getId(),
            'event' => [
                'summary' => $event->getSummary(),
                'startAt' => $event->getStartAt()?->setTimezone($paris)->format(\DATE_ATOM),
                'location' => $event->getLocation(),
            ],
            'mode' => $trip->getMode()->value,
            'origin' => [
                'kind' => $trip->getOriginKind(),
                'id' => $trip->getOriginRef(),
                'label' => $trip->getOriginLabel(),
                'latitude' => $trip->getOriginLatitude(),
                'longitude' => $trip->getOriginLongitude(),
            ],
            'originDecidedBy' => $trip->getOriginDecidedBy()->value,
            'steps' => $trip->getSteps(),
            'durationMinutes' => $trip->getDurationMinutes(),
            'departAt' => $trip->getDepartAt()->setTimezone($paris)->format(\DATE_ATOM),
            'navigationUrl' => $trip->getNavigationUrl(),
            'computedAt' => $trip->getComputedAt()->setTimezone($paris)->format(\DATE_ATOM),
        ];
        if (null !== $trip->getCarLatitude() && null !== $trip->getCarLongitude()) {
            $json['carLatitude'] = $trip->getCarLatitude();
            $json['carLongitude'] = $trip->getCarLongitude();
        }

        return $json;
    }
}
