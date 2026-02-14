<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Calendar\Contract\MercurePublishable;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Calendar\State\CreateAgendaProcessor;
use Maggie\Calendar\State\DeleteAgendaProcessor;
use Maggie\Calendar\State\UpdateAgendaProcessor;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: AgendaRepository::class)]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateAgendaProcessor::class),
    new Patch(processor: UpdateAgendaProcessor::class),
    new Delete(processor: DeleteAgendaProcessor::class),
])]
class Agenda implements MercurePublishable, OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    private string $name;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $description = null;

    #[ORM\Column(length: 50, options: ['default' => 'Europe/Paris'])]
    private string $timeZone = 'Europe/Paris';

    #[ORM\Column(length: 7, nullable: true)]
    private ?string $color = null;

    #[ORM\Column(options: ['default' => false])]
    private bool $isDefault = false;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 500, nullable: true)]
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

    /** @var Collection<int, Event> */
    #[ORM\OneToMany(targetEntity: Event::class, mappedBy: 'agenda', cascade: ['persist', 'remove'], orphanRemoval: true)]
    private Collection $events;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->events = new ArrayCollection();
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

    public function getDescription(): ?string
    {
        return $this->description;
    }

    public function setDescription(?string $description): static
    {
        $this->description = $description;

        return $this;
    }

    public function getTimeZone(): string
    {
        return $this->timeZone;
    }

    public function setTimeZone(string $timeZone): static
    {
        $this->timeZone = $timeZone;

        return $this;
    }

    public function getColor(): ?string
    {
        return $this->color;
    }

    public function setColor(?string $color): static
    {
        $this->color = $color;

        return $this;
    }

    public function isDefault(): bool
    {
        return $this->isDefault;
    }

    public function setIsDefault(bool $isDefault): static
    {
        $this->isDefault = $isDefault;

        return $this;
    }

    /** @return Collection<int, Event> */
    public function getEvents(): Collection
    {
        return $this->events;
    }

    public function addEvent(Event $event): static
    {
        if (!$this->events->contains($event)) {
            $this->events->add($event);
            $event->setAgenda($this);
        }

        return $this;
    }

    public function removeEvent(Event $event): static
    {
        $this->events->removeElement($event);

        return $this;
    }

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
        return $this->googleCalendarId !== null;
    }

    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'color' => $this->color,
            'isDefault' => $this->isDefault,
        ];
    }
}
