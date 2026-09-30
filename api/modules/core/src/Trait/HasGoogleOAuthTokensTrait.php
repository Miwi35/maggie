<?php

namespace Maggie\Core\Trait;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;

trait HasGoogleOAuthTokensTrait
{
    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleAccessToken = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleRefreshToken = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleTokenExpiresAt = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleTaskListId = null;

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

    public function getGoogleTaskListId(): ?string
    {
        return $this->googleTaskListId;
    }

    public function setGoogleTaskListId(?string $googleTaskListId): static
    {
        $this->googleTaskListId = $googleTaskListId;

        return $this;
    }

    public function hasGoogleCalendarTokens(): bool
    {
        return null !== $this->googleRefreshToken;
    }
}
