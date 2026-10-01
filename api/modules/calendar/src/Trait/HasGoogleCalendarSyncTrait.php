<?php

namespace Maggie\Calendar\Trait;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;

trait HasGoogleCalendarSyncTrait
{
    #[ORM\Column(length: 500, nullable: true)]
    // keyword: an identifier is matched whole, never analysed — and the clients
    // read it to tell a connected calendar from one still to connect.
    #[IndexedField(type: 'keyword')]
    private ?string $googleCalendarId = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $googleSyncToken = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $lastGoogleSyncAt = null;

    #[ORM\Column(length: 64, nullable: true)]
    private ?string $googleWatchChannelId = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleWatchResourceId = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleWatchExpiresAt = null;

    public function getGoogleCalendarId(): ?string
    {
        return $this->googleCalendarId;
    }

    public function setGoogleCalendarId(?string $googleCalendarId): static
    {
        $this->googleCalendarId = $googleCalendarId;

        return $this;
    }

    public function getGoogleSyncToken(): ?string
    {
        return $this->googleSyncToken;
    }

    public function setGoogleSyncToken(?string $googleSyncToken): static
    {
        $this->googleSyncToken = $googleSyncToken;

        return $this;
    }

    public function getLastGoogleSyncAt(): ?\DateTimeImmutable
    {
        return $this->lastGoogleSyncAt;
    }

    public function setLastGoogleSyncAt(?\DateTimeImmutable $lastGoogleSyncAt): static
    {
        $this->lastGoogleSyncAt = $lastGoogleSyncAt;

        return $this;
    }

    public function getGoogleWatchChannelId(): ?string
    {
        return $this->googleWatchChannelId;
    }

    public function setGoogleWatchChannelId(?string $googleWatchChannelId): static
    {
        $this->googleWatchChannelId = $googleWatchChannelId;

        return $this;
    }

    public function getGoogleWatchResourceId(): ?string
    {
        return $this->googleWatchResourceId;
    }

    public function setGoogleWatchResourceId(?string $googleWatchResourceId): static
    {
        $this->googleWatchResourceId = $googleWatchResourceId;

        return $this;
    }

    public function getGoogleWatchExpiresAt(): ?\DateTimeImmutable
    {
        return $this->googleWatchExpiresAt;
    }

    public function setGoogleWatchExpiresAt(?\DateTimeImmutable $googleWatchExpiresAt): static
    {
        $this->googleWatchExpiresAt = $googleWatchExpiresAt;

        return $this;
    }

    public function isGoogleSynced(): bool
    {
        return null !== $this->googleCalendarId;
    }
}
