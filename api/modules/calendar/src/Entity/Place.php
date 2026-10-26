<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * A place the user goes to (MAG-652, « Partir à l'heure »): home — the default
 * origin of a trip and where the car stands — or a usual destination, with the
 * parking a trip by car ends at. Saved in conversation (`save_place`), no screen.
 *
 * Coordinates are null until the address is geocoded; a place without them is
 * never matched ({@see \Maggie\Calendar\Service\PlaceMatcher}). At most one
 * home per user, kept by {@see \Maggie\Calendar\MessageHandler\SavePlaceHandler}.
 * Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: PlaceRepository::class)]
#[ORM\Index(name: 'idx_place_user', columns: ['user_id'])]
class Place implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 255)]
    private string $name = '';

    #[ORM\Column(length: 500)]
    private string $address = '';

    #[ORM\Column(nullable: true)]
    private ?float $latitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $longitude = null;

    #[ORM\Column(length: 500, nullable: true)]
    private ?string $parkingAddress = null;

    #[ORM\Column(nullable: true)]
    private ?float $parkingLatitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $parkingLongitude = null;

    #[ORM\Column(options: ['default' => false])]
    private bool $isHome = false;

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

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = $name;

        return $this;
    }

    public function getAddress(): string
    {
        return $this->address;
    }

    public function setAddress(string $address): static
    {
        $this->address = $address;

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

    /** Both or neither: a place is located by the pair. */
    public function setCoordinates(?float $latitude, ?float $longitude): static
    {
        $this->latitude = null === $longitude ? null : $latitude;
        $this->longitude = null === $latitude ? null : $longitude;

        return $this;
    }

    public function hasCoordinates(): bool
    {
        return null !== $this->latitude && null !== $this->longitude;
    }

    public function getParkingAddress(): ?string
    {
        return $this->parkingAddress;
    }

    public function setParkingAddress(?string $parkingAddress): static
    {
        $this->parkingAddress = $parkingAddress;

        return $this;
    }

    public function getParkingLatitude(): ?float
    {
        return $this->parkingLatitude;
    }

    public function getParkingLongitude(): ?float
    {
        return $this->parkingLongitude;
    }

    public function setParkingCoordinates(?float $latitude, ?float $longitude): static
    {
        $this->parkingLatitude = null === $longitude ? null : $latitude;
        $this->parkingLongitude = null === $latitude ? null : $longitude;

        return $this;
    }

    public function isHome(): bool
    {
        return $this->isHome;
    }

    public function setIsHome(bool $isHome): static
    {
        $this->isHome = $isHome;

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
            'name' => $this->name,
            'address' => $this->address,
            'latitude' => $this->latitude,
            'longitude' => $this->longitude,
            'parkingAddress' => $this->parkingAddress,
            'parkingLatitude' => $this->parkingLatitude,
            'parkingLongitude' => $this->parkingLongitude,
            'isHome' => $this->isHome,
            'createdAt' => $this->createdAt->format(\DATE_ATOM),
        ], $changedProperties);
    }
}
