<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * A daily or recurring habit (MAG-511): Maggie's own, neither an agenda task
 * nor a Google task, never synchronised. Anchored to a moment of the day
 * ({@see DayMoment}) or an hour, repeated every day, on weekdays, one day a
 * week or one day a month, ticked once a day ({@see RoutineCheck}).
 *
 * What it shows is what was done — totals, never a streak.
 * Read and written through `/api/routines`. Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: RoutineRepository::class)]
#[ORM\Index(name: 'idx_routine_user', columns: ['user_id'])]
class Routine implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const ANCHOR_TIME = 'time';
    /** The moments, in the order of the day, then a fixed hour. */
    public const ANCHORS = ['wake', 'afterBreakfast', 'afterLunch', 'afterDinner', self::ANCHOR_TIME];

    public const DAILY = 'daily';
    public const WEEKDAYS = 'weekdays';
    public const WEEKLY = 'weekly';
    public const MONTHLY = 'monthly';
    public const KINDS = [self::DAILY, self::WEEKDAYS, self::WEEKLY, self::MONTHLY];

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 255)]
    private string $title = '';

    /** Among {@see self::ANCHORS}. */
    #[ORM\Column(length: 20)]
    private string $anchor = 'wake';

    /** `HH:MM` in the user's timezone, set when and only when the anchor is {@see self::ANCHOR_TIME}. */
    #[ORM\Column(length: 5, nullable: true)]
    private ?string $anchorTime = null;

    /** @var array{kind: string, weekday?: int, monthDay?: int} */
    #[ORM\Column(type: Types::JSON)]
    private array $recurrence = ['kind' => self::DAILY];

    /** An inactive routine is due no day; its ticks and totals stay. */
    #[ORM\Column(options: ['default' => true])]
    private bool $active = true;

    /** Read by the reminders (MAG-512). Off unless asked for. */
    #[ORM\Column(options: ['default' => false])]
    private bool $remind = false;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    public function getTitle(): string
    {
        return $this->title;
    }

    /** @throws \InvalidArgumentException on a blank title */
    public function setTitle(string $title): static
    {
        $title = trim($title);
        if ('' === $title || mb_strlen($title) > 255) {
            throw new \InvalidArgumentException('title: a routine needs a title, 255 characters at most.');
        }
        $this->title = $title;

        return $this;
    }

    public function getAnchor(): string
    {
        return $this->anchor;
    }

    public function getAnchorTime(): ?string
    {
        return $this->anchorTime;
    }

    /**
     * Both at once, since one decides whether the other is allowed.
     *
     * @throws \InvalidArgumentException on an unknown anchor, or an hour given, missing or malformed
     */
    public function setAnchor(string $anchor, ?string $anchorTime = null): static
    {
        if (!\in_array($anchor, self::ANCHORS, true)) {
            throw new \InvalidArgumentException(\sprintf('anchor: unknown value "%s". Known values: %s.', $anchor, implode(', ', self::ANCHORS)));
        }
        if (self::ANCHOR_TIME === $anchor) {
            if (null === $anchorTime || 1 !== preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $anchorTime)) {
                throw new \InvalidArgumentException('anchorTime: with anchor "time", a time "HH:MM" from 00:00 to 23:59 is required.');
            }
        } elseif (null !== $anchorTime) {
            throw new \InvalidArgumentException('anchorTime: only with anchor "time".');
        }
        $this->anchor = $anchor;
        $this->anchorTime = $anchorTime;

        return $this;
    }

    /** @return array{kind: string, weekday?: int, monthDay?: int} */
    public function getRecurrence(): array
    {
        return $this->recurrence;
    }

    public function getKind(): string
    {
        return $this->recurrence['kind'];
    }

    /**
     * `weekday` 1 (Monday) to 7 with `weekly`, `monthDay` 1 to 31 with
     * `monthly` — the last day of a shorter month — and nothing else.
     *
     * @param array<mixed> $recurrence
     *
     * @throws \InvalidArgumentException
     */
    public function setRecurrence(array $recurrence): static
    {
        $kind = $recurrence['kind'] ?? null;
        if (!\is_string($kind) || !\in_array($kind, self::KINDS, true)) {
            throw new \InvalidArgumentException(\sprintf('recurrence.kind: one of %s.', implode(', ', self::KINDS)));
        }
        $weekday = $recurrence['weekday'] ?? null;
        $monthDay = $recurrence['monthDay'] ?? null;

        $this->recurrence = match ($kind) {
            self::WEEKLY => ['kind' => $kind, 'weekday' => self::bounded($weekday, 1, 7, 'recurrence.weekday: with "weekly", a day from 1 (Monday) to 7 (Sunday).')],
            self::MONTHLY => ['kind' => $kind, 'monthDay' => self::bounded($monthDay, 1, 31, 'recurrence.monthDay: with "monthly", a day from 1 to 31.')],
            default => ['kind' => $kind],
        };

        return $this;
    }

    public function isActive(): bool
    {
        return $this->active;
    }

    public function setActive(bool $active): static
    {
        $this->active = $active;

        return $this;
    }

    public function isRemind(): bool
    {
        return $this->remind;
    }

    public function setRemind(bool $remind): static
    {
        $this->remind = $remind;

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    public function setCreatedAt(\DateTimeImmutable $createdAt): static
    {
        $this->createdAt = $createdAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'title' => $this->title,
            'anchor' => $this->anchor,
            'anchorTime' => $this->anchorTime,
            'recurrence' => $this->recurrence,
            'active' => $this->active,
            'remind' => $this->remind,
            'createdAt' => $this->createdAt->format(\DATE_ATOM),
        ], $changedProperties);
    }

    private static function bounded(mixed $value, int $min, int $max, string $error): int
    {
        if (!\is_int($value) || $value < $min || $value > $max) {
            throw new \InvalidArgumentException($error);
        }

        return $value;
    }
}
