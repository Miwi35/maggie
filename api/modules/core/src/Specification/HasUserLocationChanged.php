<?php

declare(strict_types=1);

namespace Maggie\Core\Specification;

use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Event\UserLocationChangedEvent;

/**
 * The position (or the car's place) moved: one of its fields holds another
 * value than before. A creation has no « before »; a field written again with
 * the same value — a re-sent report, a new DateTime of the same instant — is not a change.
 */
final readonly class HasUserLocationChanged
{
    private const FIELDS = [
        UserLocationChangedEvent::POSITION => [
            'gpsLatitude', 'gpsLongitude', 'gpsAccuracy', 'gpsCapturedAt',
            'networkLatitude', 'networkLongitude', 'networkAccuracy', 'networkCapturedAt',
        ],
        UserLocationChangedEvent::CAR => ['carLatitude', 'carLongitude', 'carParkedAt'],
    ];

    /**
     * @param array<string, array{0: mixed, 1: mixed}>                         $changeSet what Doctrine is about to write: field => [before, after]
     * @param UserLocationChangedEvent::POSITION|UserLocationChangedEvent::CAR $what
     */
    public function __construct(
        private array $changeSet,
        private string $what,
    ) {
    }

    public function isSatisfiedBy(UserLocation $location): bool
    {
        foreach (self::FIELDS[$this->what] as $field) {
            if (!isset($this->changeSet[$field])) {
                continue;
            }

            [$before, $after] = $this->changeSet[$field];
            if (null === $after) {
                continue;
            }
            if ($before instanceof \DateTimeInterface && $after instanceof \DateTimeInterface) {
                // Doctrine compares dates by identity: the same instant, re-sent, looks changed.
                if ($before->format('U.u') !== $after->format('U.u')) {
                    return true;
                }

                continue;
            }
            if ($before !== $after) {
                return true;
            }
        }

        return false;
    }
}
