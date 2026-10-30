<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Health\Enum\DailyActivitySource;
use Maggie\Health\Repository\DailyActivityRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A day's steps and active energy (MAG-708): one row per user and local date,
 * as the phone's Health Connect aggregates them — its sources already
 * de-duplicated. Written by `POST /api/health/sync` only
 * ({@see \Maggie\Health\Controller\HealthSyncController}), read by the steps
 * and consolidated energy of a later part. Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: DailyActivityRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_daily_activity_user_date', columns: ['user_id', 'date'])]
#[MercureCollection(self::COLLECTION)]
class DailyActivity implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const COLLECTION = '/api/daily-activities';

    public const MAX_STEPS = 200_000;

    public const MAX_ACTIVE_KCAL = 20_000;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    /** The day in the user's timezone (midnight, a date carries no zone). */
    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    private \DateTimeImmutable $date;

    #[ORM\Column(type: 'integer')]
    private int $steps = 0;

    #[ORM\Column(type: 'integer')]
    private int $activeKcal = 0;

    #[ORM\Column(length: 16, enumType: DailyActivitySource::class)]
    private DailyActivitySource $source = DailyActivitySource::HealthConnect;

    public function __construct()
    {
        $this->id = new Ulid();
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

    public function getDate(): \DateTimeImmutable
    {
        return $this->date;
    }

    public function setDate(\DateTimeImmutable $date): static
    {
        $this->date = $date;

        return $this;
    }

    public function getSteps(): int
    {
        return $this->steps;
    }

    public function setSteps(int $steps): static
    {
        $this->steps = $steps;

        return $this;
    }

    public function getActiveKcal(): int
    {
        return $this->activeKcal;
    }

    public function setActiveKcal(int $activeKcal): static
    {
        $this->activeKcal = $activeKcal;

        return $this;
    }

    public function getSource(): DailyActivitySource
    {
        return $this->source;
    }

    public function setSource(DailyActivitySource $source): static
    {
        $this->source = $source;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'id' => (string) $this->id,
            'date' => $this->date->format('Y-m-d'),
            'steps' => $this->steps,
            'activeKcal' => $this->activeKcal,
            'source' => $this->source->value,
        ], $changedProperties);
    }
}
