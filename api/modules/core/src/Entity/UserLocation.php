<?php

declare(strict_types=1);

namespace Maggie\Core\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Enum\LocationSource;
use Maggie\Core\Location\LocationFix;
use Maggie\Core\Repository\UserLocationRepository;
use Symfony\Component\Uid\Ulid;

/**
 * Where the owner last was and where the car is parked (MAG-650): one row per
 * user, overwritten on every report, never a history. Read by the trip planner
 * to work out when to leave; no front shows it, so neither Mercure nor
 * Elasticsearch carries it.
 *
 * The last GPS position and the last network position are kept apart (MAG-666): a
 * network fix never overwrites a GPS one. Which of the two stands for the user is
 * decided in one place, {@see \Maggie\Core\Service\UserPositions}.
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
    private ?float $networkLatitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $networkLongitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $networkAccuracy = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $networkCapturedAt = null;

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

    public function getGpsFix(): ?LocationFix
    {
        return $this->fix(LocationSource::Gps);
    }

    public function getNetworkFix(): ?LocationFix
    {
        return $this->fix(LocationSource::Network);
    }

    /** Replaces the last position of the fix's own source; the other source's is left alone. */
    public function moveTo(LocationSource $source, float $latitude, float $longitude, float $accuracy, \DateTimeImmutable $capturedAt): static
    {
        $capturedAt = self::toSecond($capturedAt);
        if (LocationSource::Gps === $source) {
            [$this->gpsLatitude, $this->gpsLongitude, $this->gpsAccuracy, $this->gpsCapturedAt] = [$latitude, $longitude, $accuracy, $capturedAt];
        } else {
            [$this->networkLatitude, $this->networkLongitude, $this->networkAccuracy, $this->networkCapturedAt] = [$latitude, $longitude, $accuracy, $capturedAt];
        }

        return $this;
    }

    private function fix(LocationSource $source): ?LocationFix
    {
        [$latitude, $longitude, $accuracy, $capturedAt] = LocationSource::Gps === $source
            ? [$this->gpsLatitude, $this->gpsLongitude, $this->gpsAccuracy, $this->gpsCapturedAt]
            : [$this->networkLatitude, $this->networkLongitude, $this->networkAccuracy, $this->networkCapturedAt];

        return null === $latitude || null === $longitude || null === $accuracy || null === $capturedAt
            ? null
            : new LocationFix($latitude, $longitude, $accuracy, $capturedAt, $source);
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
