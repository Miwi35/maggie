<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercureItemKey;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Health\Dto\HealthDayFigures;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Repository\HealthDayRepository;
use Symfony\Component\Uid\Ulid;

/**
 * The health journal (MAG-750, spec part 16): one row per user and local day,
 * one typed column per indicator. The computed columns summarise the detailed
 * tables (weighings, sessions, intakes, Health Connect's day) and are kept up
 * to date from them ({@see \Maggie\Health\UseCase\RefreshHealthDay}); mood,
 * fatigue and the note are typed, by the user or by Maggie, and never touched
 * by a refresh. Only what was done is kept: today's balance, planned meals
 * included, is computed live.
 *
 * Read and written through `/api/health/days`, never through an API Platform
 * operation: the clients know a day by its date, which is also its Mercure key.
 */
#[ORM\Entity(repositoryClass: HealthDayRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_health_day_user_date', columns: ['user_id', 'date'])]
#[MercureCollection(self::COLLECTION)]
class HealthDay implements MercurePublishable, MercureItemKey, OwnedByUserInterface
{
    public const COLLECTION = '/api/health/days';

    public const MIN_SCORE = 1;

    public const MAX_SCORE = 10;

    public const MAX_NOTE_LENGTH = 2000;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    /** The local day (Europe/Paris), midnight to midnight. */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $date;

    // DECIMAL columns are read as strings by Doctrine: the float accessors give numbers.
    #[ORM\Column(name: 'weight_kg', type: Types::DECIMAL, precision: 5, scale: 2, nullable: true)]
    private ?string $weight = null;

    #[ORM\Column(name: 'body_fat_percent', type: Types::DECIMAL, precision: 4, scale: 1, nullable: true)]
    private ?string $bodyFat = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $weighedAt = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $steps = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $eatenKcal = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $expendedKcal = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $sportMinutes = null;

    #[ORM\Column(type: Types::SMALLINT, nullable: true)]
    private ?int $mood = null;

    #[ORM\Column(type: Types::SMALLINT, nullable: true)]
    private ?int $fatigue = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $note = null;

    /** Who last wrote mood, fatigue or the note, and when: « avec Maggie · 21:42 ». */
    #[ORM\Column(length: 16, nullable: true, enumType: HealthDayAuthor::class)]
    private ?HealthDayAuthor $notedBy = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $notedAt = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getMercureItemKey(): string
    {
        return $this->date->format('Y-m-d');
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

    public function getDate(): \DateTimeImmutable
    {
        return $this->date;
    }

    public function setDate(\DateTimeImmutable $date): static
    {
        $this->date = $date;

        return $this;
    }

    public function getWeightKg(): ?float
    {
        return null === $this->weight ? null : (float) $this->weight;
    }

    public function getBodyFatPercent(): ?float
    {
        return null === $this->bodyFat ? null : (float) $this->bodyFat;
    }

    public function getWeighedAt(): ?\DateTimeImmutable
    {
        return $this->weighedAt;
    }

    public function getSteps(): ?int
    {
        return $this->steps;
    }

    public function getEatenKcal(): ?int
    {
        return $this->eatenKcal;
    }

    public function getExpendedKcal(): ?int
    {
        return $this->expendedKcal;
    }

    public function getSportMinutes(): ?int
    {
        return $this->sportMinutes;
    }

    /**
     * Lays down the computed columns. A column whose value is unchanged is
     * left alone, so that a refresh that finds nothing new changes nothing.
     */
    public function record(HealthDayFigures $figures): static
    {
        $weight = null === $figures->weightKg ? null : number_format($figures->weightKg, 2, '.', '');
        $bodyFat = null === $figures->bodyFatPercent ? null : number_format($figures->bodyFatPercent, 1, '.', '');
        if ($weight !== $this->weight) {
            $this->weight = $weight;
        }
        if ($bodyFat !== $this->bodyFat) {
            $this->bodyFat = $bodyFat;
        }
        if ($figures->weighedAt?->getTimestamp() !== $this->weighedAt?->getTimestamp()) {
            $this->weighedAt = $figures->weighedAt;
        }
        $this->steps = $figures->steps;
        $this->eatenKcal = $figures->eatenKcal;
        $this->expendedKcal = $figures->expendedKcal;
        $this->sportMinutes = $figures->sportMinutes;

        return $this;
    }

    public function getMood(): ?int
    {
        return $this->mood;
    }

    public function setMood(?int $mood): static
    {
        $this->mood = $mood;

        return $this;
    }

    public function getFatigue(): ?int
    {
        return $this->fatigue;
    }

    public function setFatigue(?int $fatigue): static
    {
        $this->fatigue = $fatigue;

        return $this;
    }

    public function getNote(): ?string
    {
        return $this->note;
    }

    public function setNote(?string $note): static
    {
        $this->note = $note;

        return $this;
    }

    public function getNotedBy(): ?HealthDayAuthor
    {
        return $this->notedBy;
    }

    public function getNotedAt(): ?\DateTimeImmutable
    {
        return $this->notedAt;
    }

    public function noted(HealthDayAuthor $by, \DateTimeImmutable $at): static
    {
        $this->notedBy = $by;
        $this->notedAt = $at;

        return $this;
    }

    /** Nothing typed: mood, fatigue and the note are all empty. */
    public function hasNothingTyped(): bool
    {
        return null === $this->mood && null === $this->fatigue && null === $this->note;
    }

    /**
     * What the endpoint, the tools and Mercure give; `balanceKcal` is what was
     * eaten minus what was spent, null while either is unknown.
     *
     * @return array<string, mixed>
     */
    public function describe(): array
    {
        return [
            'id' => (string) $this->id,
            'date' => $this->date->format('Y-m-d'),
            'weightKg' => $this->getWeightKg(),
            'bodyFatPercent' => $this->getBodyFatPercent(),
            'weighedAt' => $this->weighedAt?->format(\DATE_ATOM),
            'steps' => $this->steps,
            'eatenKcal' => $this->eatenKcal,
            'expendedKcal' => $this->expendedKcal,
            'balanceKcal' => null === $this->eatenKcal || null === $this->expendedKcal ? null : $this->eatenKcal - $this->expendedKcal,
            'sportMinutes' => $this->sportMinutes,
            'mood' => $this->mood,
            'fatigue' => $this->fatigue,
            'note' => $this->note,
            'notedBy' => $this->notedBy?->value,
            'notedAt' => $this->notedAt?->format(\DATE_ATOM),
        ];
    }

    /**
     * The entry of a day nothing was recorded on yet, same keys as {@see self::describe()}.
     *
     * @return array<string, mixed>
     */
    public static function empty(string $date): array
    {
        return [
            'id' => null,
            'date' => $date,
            'weightKg' => null,
            'bodyFatPercent' => null,
            'weighedAt' => null,
            'steps' => null,
            'eatenKcal' => null,
            'expendedKcal' => null,
            'balanceKcal' => null,
            'sportMinutes' => null,
            'mood' => null,
            'fatigue' => null,
            'note' => null,
            'notedBy' => null,
            'notedAt' => null,
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return $this->describe();
    }
}
