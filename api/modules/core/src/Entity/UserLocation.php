<?php

declare(strict_types=1);

namespace Maggie\Core\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Repository\UserLocationRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Where the owner last was and where the car is parked (MAG-650): one row per
 * user, overwritten on every report, never a history. Read by the trip planner
 * to work out when to leave; no front shows it, so neither Mercure nor
 * Elasticsearch carries it.
 *
 * Either half may be missing: the phone can report the car before any position.
 */
#[ORM\Entity(repositoryClass: UserLocationRepository::class)]
class UserLocation implements OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\OneToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, unique: true, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(nullable: true)]
    private ?float $latitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $longitude = null;

    /** Radius in metres the phone vouches for. */
    #[ORM\Column(nullable: true)]
    private ?float $accuracy = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $capturedAt = null;

    #[ORM\Column(nullable: true)]
    private ?float $carLatitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $carLongitude = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $carParkedAt = null;

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

    public function getLatitude(): ?float
    {
        return $this->latitude;
    }

    public function getLongitude(): ?float
    {
        return $this->longitude;
    }

    public function getAccuracy(): ?float
    {
        return $this->accuracy;
    }

    public function getCapturedAt(): ?\DateTimeImmutable
    {
        return $this->capturedAt;
    }

    public function moveTo(float $latitude, float $longitude, float $accuracy, \DateTimeImmutable $capturedAt): static
    {
        $this->latitude = $latitude;
        $this->longitude = $longitude;
        $this->accuracy = $accuracy;
        $this->capturedAt = self::toSecond($capturedAt);

        return $this;
    }

    public function getCarLatitude(): ?float
    {
        return $this->carLatitude;
    }

    public function getCarLongitude(): ?float
    {
        return $this->carLongitude;
    }

    public function getCarParkedAt(): ?\DateTimeImmutable
    {
        return $this->carParkedAt;
    }

    public function parkCar(float $latitude, float $longitude, \DateTimeImmutable $parkedAt): static
    {
        $this->carLatitude = $latitude;
        $this->carLongitude = $longitude;
        $this->carParkedAt = self::toSecond($parkedAt);

        return $this;
    }

    /** The column keeps whole seconds: a fraction kept in memory would make the same report look new once reloaded. */
    private static function toSecond(\DateTimeImmutable $at): \DateTimeImmutable
    {
        return $at->setTime((int) $at->format('H'), (int) $at->format('i'), (int) $at->format('s'));
    }
}
