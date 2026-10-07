<?php

declare(strict_types=1);

namespace Maggie\Notification\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Enum\DevicePlatform;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A device Maggie can reach by push: the FCM registration token the app got
 * from Firebase (MAG-26).
 *
 * Not an API resource and never published on Mercure: a token is enough to
 * push to the phone, so it goes in through `/api/fcm_tokens` and never comes
 * back out.
 */
#[ORM\Entity(repositoryClass: DeviceTokenRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_device_token_token', columns: ['token'])]
class DeviceToken implements OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: 512)]
    private string $token;

    #[ORM\Column(length: 10, enumType: DevicePlatform::class)]
    private DevicePlatform $platform = DevicePlatform::Android;

    #[ORM\Column(length: 100, nullable: true)]
    private ?string $deviceName = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    /** Refreshed each time the app registers the token again: the last sign the device is alive. */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $lastSeenAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
        $this->lastSeenAt = $this->createdAt;
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

    public function getToken(): string
    {
        return $this->token;
    }

    public function setToken(string $token): static
    {
        $this->token = $token;

        return $this;
    }

    public function getPlatform(): DevicePlatform
    {
        return $this->platform;
    }

    public function setPlatform(DevicePlatform $platform): static
    {
        $this->platform = $platform;

        return $this;
    }

    public function getDeviceName(): ?string
    {
        return $this->deviceName;
    }

    public function setDeviceName(?string $deviceName): static
    {
        $this->deviceName = $deviceName;

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    public function getLastSeenAt(): \DateTimeImmutable
    {
        return $this->lastSeenAt;
    }

    public function setLastSeenAt(\DateTimeImmutable $lastSeenAt): static
    {
        $this->lastSeenAt = $lastSeenAt;

        return $this;
    }
}
