<?php

namespace Maggie\Calendar\Trait;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;

trait HasGoogleTaskTrackingTrait
{
    #[ORM\Column(length: 1024, nullable: true)]
    private ?string $googleTaskId = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleTaskListId = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $googleTaskEtag = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $googleTaskUpdatedAt = null;

    public function getGoogleTaskId(): ?string
    {
        return $this->googleTaskId;
    }

    public function setGoogleTaskId(?string $googleTaskId): static
    {
        $this->googleTaskId = $googleTaskId;

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

    public function getGoogleTaskEtag(): ?string
    {
        return $this->googleTaskEtag;
    }

    public function setGoogleTaskEtag(?string $googleTaskEtag): static
    {
        $this->googleTaskEtag = $googleTaskEtag;

        return $this;
    }

    public function getGoogleTaskUpdatedAt(): ?\DateTimeImmutable
    {
        return $this->googleTaskUpdatedAt;
    }

    public function setGoogleTaskUpdatedAt(?\DateTimeImmutable $googleTaskUpdatedAt): static
    {
        $this->googleTaskUpdatedAt = $googleTaskUpdatedAt;

        return $this;
    }

    public function isGoogleSynced(): bool
    {
        return $this->googleTaskId !== null;
    }
}
