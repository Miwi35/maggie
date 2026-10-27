<?php

declare(strict_types=1);

namespace Maggie\Calendar\Specification;

use Maggie\Calendar\Entity\Event;

/**
 * An event of today (Paris) was created with a location, moved or relocated, or removed (MAG-656):
 * the trips of the day may no longer hold.
 *
 * Only events of the user's own agendas count — never a module agenda, the « Maggie » one
 * included, whose proactions Maggie files herself. An event is « of today » before or after the
 * change: one moved out of today, or into it, changes the day's trips as well.
 */
final readonly class HasTodayEventChanged
{
    public const string TIME_ZONE = 'Europe/Paris';

    private const CREATED = 'created';
    private const UPDATED = 'updated';
    private const REMOVED = 'removed';

    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    private function __construct(
        private string $transition,
        private array $changeSet,
        private \DateTimeImmutable $now,
    ) {
    }

    public static function created(\DateTimeImmutable $now): self
    {
        return new self(self::CREATED, [], $now);
    }

    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet */
    public static function updated(array $changeSet, \DateTimeImmutable $now): self
    {
        return new self(self::UPDATED, $changeSet, $now);
    }

    public static function removed(\DateTimeImmutable $now): self
    {
        return new self(self::REMOVED, [], $now);
    }

    public function isSatisfiedBy(Event $event): bool
    {
        if ($event->getAgenda()->isModule()) {
            return false;
        }

        return match ($this->transition) {
            self::CREATED => $this->hasLocation($event->getLocation()) && $this->isToday($event->getStartAt()),
            self::REMOVED => $this->isToday($event->getStartAt()),
            default => $this->isUpdateOfToday($event),
        };
    }

    private function isUpdateOfToday(Event $event): bool
    {
        $startChanged = isset($this->changeSet['startAt']) && $this->differs(...$this->changeSet['startAt']);
        $locationChanged = isset($this->changeSet['location']) && $this->differs(...$this->changeSet['location']);
        if (!$startChanged && !$locationChanged) {
            return false;
        }

        $before = $startChanged ? $this->changeSet['startAt'][0] : $event->getStartAt();

        return $this->isToday($before) || $this->isToday($event->getStartAt());
    }

    /** Doctrine compares dates by identity: the same instant, written again, looks changed. */
    private function differs(mixed $before, mixed $after): bool
    {
        if ($before instanceof \DateTimeInterface && $after instanceof \DateTimeInterface) {
            return $before->format('U') !== $after->format('U');
        }

        return $before !== $after;
    }

    private function hasLocation(?string $location): bool
    {
        return null !== $location && '' !== trim($location);
    }

    private function isToday(mixed $startAt): bool
    {
        if (!$startAt instanceof \DateTimeImmutable) {
            return false;
        }

        $zone = new \DateTimeZone(self::TIME_ZONE);

        return $startAt->setTimezone($zone)->format('Y-m-d') === $this->now->setTimezone($zone)->format('Y-m-d');
    }
}
