<?php

namespace Maggie\Calendar\Trait;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;

trait HasGoogleEventTrackingTrait
{
    #[ORM\Column(length: 1024, nullable: true)]
    private ?string $googleEventId = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleEtag = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleUpdatedAt = null;

    public function getGoogleEventId(): ?string
    {
        return $this->googleEventId;
    }

    public function setGoogleEventId(?string $googleEventId): static
    {
        $this->googleEventId = $googleEventId;

        return $this;
    }

    public function getGoogleEtag(): ?string
    {
        return $this->googleEtag;
    }

    public function setGoogleEtag(?string $googleEtag): static
    {
        $this->googleEtag = $googleEtag;

        return $this;
    }

    public function getGoogleUpdatedAt(): ?\DateTimeImmutable
    {
        return $this->googleUpdatedAt;
    }

    public function setGoogleUpdatedAt(?\DateTimeImmutable $googleUpdatedAt): static
    {
        $this->googleUpdatedAt = $googleUpdatedAt;

        return $this;
    }

    public function isGoogleSynced(): bool
    {
        return $this->googleEventId !== null;
    }
}
