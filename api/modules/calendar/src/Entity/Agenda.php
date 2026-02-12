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
class Agenda implements MercurePublishable
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

    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'color' => $this->color,
            'isDefault' => $this->isDefault,
        ];
    }
}
