<?php

declare(strict_types=1);

namespace Maggie\Core\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Location\LocationFix;
use Maggie\Core\Repository\UserLocationRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Where the owner last was and where the car is parked (MAG-650): one row per
 * user, overwritten on every report, never a history. Read by the trip planner
 * to work out when to leave; no front shows it, so neither Mercure nor
 * Elasticsearch carries it.
 *
 * Only GPS positions are kept (MAG-666): the phone sends no other, and a network
 * fix, which can be kilometres off while claiming a few hundred metres, is dropped
 * on the way in. The last GPS point stays the position whatever its age.
 *
 * Any part may be missing: the phone can report the car before any position.
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
    private ?float $gpsLatitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $gpsLongitude = null;

    /** Radius in metres the phone vouches for. */
    #[ORM\Column(nullable: true)]
    private ?float $gpsAccuracy = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $gpsCapturedAt = null;

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

    public function getFix(): ?LocationFix
    {
        return null === $this->gpsLatitude || null === $this->gpsLongitude || null === $this->gpsAccuracy || null === $this->gpsCapturedAt
            ? null
            : new LocationFix($this->gpsLatitude, $this->gpsLongitude, $this->gpsAccuracy, $this->gpsCapturedAt);
    }

    /** Replaces the last position. */
    public function moveTo(float $latitude, float $longitude, float $accuracy, \DateTimeImmutable $capturedAt): static
    {
        [$this->gpsLatitude, $this->gpsLongitude, $this->gpsAccuracy, $this->gpsCapturedAt] = [$latitude, $longitude, $accuracy, self::toSecond($capturedAt)];

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
