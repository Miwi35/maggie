<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Repository\CookingTimerRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Uid\Ulid;

/**
 * A timer of the Cuisine activity (MAG-582): the end of a cooking step, which
 * every device of the user hears about.
 *
 * The instant lives on the device that runs the activity; the end lives here,
 * because it counts elsewhere: `endsAt` is the authority a reload or a rotation
 * reads back, and the worker rings it ({@see \Maggie\Cookbook\Message\FinishCookingTimerCommand}).
 * Every change of the schedule (pause, resume, ±1 min, cancel) bumps
 * `revision`, so the end queued for the old schedule finds a different one and
 * does nothing: an end is notified once, and never after a pause or a cancel.
 *
 * Served by `/api/cooking/timers` (CookingTimerController), not by API Platform:
 * a change is an action (`pause`, `add`…), not a set of fields. Not searched,
 * so not indexed.
 */
#[ORM\Entity(repositoryClass: CookingTimerRepository::class)]
#[ORM\Index(columns: ['user_id', 'status'], name: 'idx_cooking_timer_user_status')]
#[MercureCollection(self::COLLECTION)]
class CookingTimer implements MercurePublishable, OwnedByUserInterface
{
    /** The collection path, and the Mercure topic the clients subscribe to. */
    public const COLLECTION = '/api/cooking/timers';

    public const MAX_LABEL_LENGTH = 80;
    public const MAX_DURATION_SECONDS = 86400;
    /** How far one `add` moves a timer, either way. */
    public const MAX_ADD_SECONDS = 3600;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: self::MAX_LABEL_LENGTH)]
    private string $label;

    // Null once the recipe is deleted: the timer outlives it rather than block it.
    #[ORM\ManyToOne(targetEntity: Recipe::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Recipe $recipe = null;

    #[ORM\Column]
    private int $stepPosition;

    /** The whole length, ±1 min included: a ring shows what is left of it. */
    #[ORM\Column]
    private int $durationSeconds;

    /** When it rings; null while paused. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $endsAt = null;

    /** What is left, kept while paused only. */
    #[ORM\Column(nullable: true)]
    private ?int $pausedRemainingSeconds = null;

    #[ORM\Column(length: 16, enumType: CookingTimerStatus::class)]
    private CookingTimerStatus $status = CookingTimerStatus::Running;

    /** Bumped by every change of the schedule: an end queued for an older one is ignored. */
    #[ORM\Column(options: ['default' => 0])]
    private int $revision = 0;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $finishedAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    public function __construct(User $user, string $label, Recipe $recipe, int $stepPosition, int $durationSeconds, \DateTimeImmutable $now)
    {
        $label = trim($label);
        if ('' === $label || mb_strlen($label) > self::MAX_LABEL_LENGTH) {
            throw new \DomainException(\sprintf('label is required, %d characters at most.', self::MAX_LABEL_LENGTH));
        }
        if ($stepPosition < 1) {
            throw new \DomainException('stepPosition is a step of the recipe, 1 or more.');
        }
        if ($durationSeconds <= 0 || $durationSeconds > self::MAX_DURATION_SECONDS) {
            throw new \DomainException(\sprintf('durationSeconds must be between 1 and %d.', self::MAX_DURATION_SECONDS));
        }

        $this->id = new Ulid();
        $this->user = $user;
        $this->label = $label;
        $this->recipe = $recipe;
        $this->stepPosition = $stepPosition;
        $this->durationSeconds = $durationSeconds;
        $this->endsAt = $now->modify(\sprintf('+%d seconds', $durationSeconds));
        $this->createdAt = $now;
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

    public function getLabel(): string
    {
        return $this->label;
    }

    public function getRecipe(): ?Recipe
    {
        return $this->recipe;
    }

    public function getStepPosition(): int
    {
        return $this->stepPosition;
    }

    public function getDurationSeconds(): int
    {
        return $this->durationSeconds;
    }

    public function getEndsAt(): ?\DateTimeImmutable
    {
        return $this->endsAt;
    }

    public function getStatus(): CookingTimerStatus
    {
        return $this->status;
    }

    public function getRevision(): int
    {
        return $this->revision;
    }

    public function getFinishedAt(): ?\DateTimeImmutable
    {
        return $this->finishedAt;
    }

    public function remainingSeconds(\DateTimeImmutable $now): int
    {
        return match ($this->status) {
            CookingTimerStatus::Running => max(0, (null === $this->endsAt ? 0 : $this->endsAt->getTimestamp()) - $now->getTimestamp()),
            CookingTimerStatus::Paused => $this->pausedRemainingSeconds ?? 0,
            default => 0,
        };
    }

    /** Stops it, its remaining time kept. */
    public function pause(\DateTimeImmutable $now): void
    {
        $this->expect(CookingTimerStatus::Running, 'pause');
        $this->pausedRemainingSeconds = $this->remainingSeconds($now);
        $this->endsAt = null;
        $this->status = CookingTimerStatus::Paused;
        ++$this->revision;
    }

    /** Runs again from what was left: `endsAt` is recomputed. */
    public function resume(\DateTimeImmutable $now): void
    {
        $this->expect(CookingTimerStatus::Paused, 'resume');
        $this->endsAt = $now->modify(\sprintf('+%d seconds', $this->pausedRemainingSeconds ?? 0));
        $this->pausedRemainingSeconds = null;
        $this->status = CookingTimerStatus::Running;
        ++$this->revision;
    }

    /** ±1 min (or what `seconds` says), never below nothing left. */
    public function add(int $seconds, \DateTimeImmutable $now): void
    {
        if (0 === $seconds || abs($seconds) > self::MAX_ADD_SECONDS) {
            throw new \DomainException(\sprintf('seconds must be a non-zero number between -%1$d and %1$d.', self::MAX_ADD_SECONDS));
        }
        if (!\in_array($this->status, [CookingTimerStatus::Running, CookingTimerStatus::Paused], true)) {
            throw new \DomainException(\sprintf('A %s timer cannot be changed: only a running or paused one.', $this->status->value));
        }

        $remaining = max(0, $this->remainingSeconds($now) + $seconds);
        if (CookingTimerStatus::Running === $this->status) {
            $this->endsAt = $now->modify(\sprintf('+%d seconds', $remaining));
        } else {
            $this->pausedRemainingSeconds = $remaining;
        }
        $this->durationSeconds = max(1, $this->durationSeconds + $seconds, $remaining);
        ++$this->revision;
    }

    public function cancel(): void
    {
        if (!$this->status->isActive()) {
            throw new \DomainException(\sprintf('The timer is already %s.', $this->status->value));
        }
        $this->status = CookingTimerStatus::Cancelled;
        $this->pausedRemainingSeconds = null;
        ++$this->revision;
    }

    /** « OK » on its end. */
    public function dismiss(): void
    {
        $this->expect(CookingTimerStatus::Done, 'dismiss');
        $this->status = CookingTimerStatus::Dismissed;
        ++$this->revision;
    }

    /**
     * The end, as the worker reaches it: true when it rings now, false when
     * the schedule it was queued for changed (paused, moved, cancelled) or it
     * already rang.
     */
    public function finish(int $revision, \DateTimeImmutable $now): bool
    {
        if ($revision !== $this->revision || CookingTimerStatus::Running !== $this->status || null === $this->endsAt) {
            return false;
        }

        $this->status = CookingTimerStatus::Done;
        $this->finishedAt = max($now, $this->endsAt);

        return true;
    }

    private function expect(CookingTimerStatus $status, string $action): void
    {
        if ($status !== $this->status) {
            throw new \DomainException(\sprintf('Cannot %s a %s timer: only a %s one.', $action, $this->status->value, $status->value));
        }
    }

    /**
     * What every channel serves: the REST answers, the Mercure updates, the MCP tool.
     *
     * @return array{id: string, label: string, recipe: string|null, stepPosition: int, durationSeconds: int, endsAt: string|null, remainingSeconds: int, status: string, finishedAt: string|null}
     */
    public function describe(\DateTimeImmutable $now): array
    {
        return [
            'id' => (string) $this->id,
            'label' => $this->label,
            'recipe' => null === $this->recipe ? null : '/api/recipes/'.$this->recipe->getId(),
            'stepPosition' => $this->stepPosition,
            'durationSeconds' => $this->durationSeconds,
            'endsAt' => $this->endsAt?->format(\DATE_ATOM),
            'remainingSeconds' => $this->remainingSeconds($now),
            'status' => $this->status->value,
            'finishedAt' => $this->finishedAt?->format(\DATE_ATOM),
        ];
    }

    /**
     * Always whole: a screen must never apply a status without the `endsAt`
     * that goes with it.
     *
     * @return array<string, mixed>
     */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return $this->describe(Clock::get()->now());
    }
}
