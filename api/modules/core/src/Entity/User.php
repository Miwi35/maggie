<?php

namespace Maggie\Core\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: UserRepository::class)]
#[ORM\Table(name: '"user"')]
class User implements UserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255, unique: true)]
    private string $email;

    #[ORM\Column(length: 255, unique: true)]
    private string $googleId;

    #[ORM\Column(length: 255)]
    private string $name;

    #[ORM\Column(length: 512, nullable: true)]
    private ?string $avatar = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleAccessToken = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleRefreshToken = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleTokenExpiresAt = null;

    /** @var list<string> */
    #[ORM\Column(type: Types::JSON)]
    private array $roles = [];

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getEmail(): string
    {
        return $this->email;
    }

    public function setEmail(string $email): static
    {
        $this->email = $email;

        return $this;
    }

    public function getGoogleId(): string
    {
        return $this->googleId;
    }

    public function setGoogleId(string $googleId): static
    {
        $this->googleId = $googleId;

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

    public function getAvatar(): ?string
    {
        return $this->avatar;
    }

    public function setAvatar(?string $avatar): static
    {
        $this->avatar = $avatar;

        return $this;
    }

    /** @return list<string> */
    public function getRoles(): array
    {
        $roles = $this->roles;
        $roles[] = 'ROLE_USER';

        return array_values(array_unique($roles));
    }

    /** @param list<string> $roles */
    public function setRoles(array $roles): static
    {
        $this->roles = $roles;

        return $this;
    }

    public function getUserIdentifier(): string
    {
        return $this->email;
    }

    public function getGoogleAccessToken(): ?string
    {
        return $this->googleAccessToken;
    }

    public function setGoogleAccessToken(?string $googleAccessToken): static
    {
        $this->googleAccessToken = $googleAccessToken;

        return $this;
    }

    public function getGoogleRefreshToken(): ?string
    {
        return $this->googleRefreshToken;
    }

    public function setGoogleRefreshToken(?string $googleRefreshToken): static
    {
        $this->googleRefreshToken = $googleRefreshToken;

        return $this;
    }

    public function getGoogleTokenExpiresAt(): ?\DateTimeImmutable
    {
        return $this->googleTokenExpiresAt;
    }

    public function setGoogleTokenExpiresAt(?\DateTimeImmutable $googleTokenExpiresAt): static
    {
        $this->googleTokenExpiresAt = $googleTokenExpiresAt;

        return $this;
    }

    public function hasGoogleCalendarTokens(): bool
    {
        return $this->googleRefreshToken !== null;
    }

    public function eraseCredentials(): void
    {
    }
}
