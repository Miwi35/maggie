<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\State\CreateAgendaProcessor;
use Maggie\Calendar\State\DeleteAgendaProcessor;
use Maggie\Calendar\State\UpdateAgendaProcessor;
use Maggie\Calendar\Trait\HasGoogleCalendarSyncTrait;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: AgendaRepository::class)]
// One Google calendar is one agenda: connecting the same calendar twice used to
// create a second agenda, and both copies then synced and duplicated every
// event (MAG-148). NULLs count as distinct in Postgres, so the agendas with no
// Google calendar are unaffected.
#[ORM\UniqueConstraint(name: 'uniq_agenda_user_google_calendar', columns: ['user_id', 'google_calendar_id'])]
// One default agenda per user, enforced where a race between two requests cannot
// get past the handler that demotes the others (MAG-149).
#[ORM\UniqueConstraint(name: 'uniq_agenda_user_default', columns: ['user_id'], options: ['where' => '(is_default = true)'])]
// One agenda per module and per user: two first meals racing cannot both create
// « Repas » (MAG-324). NULLs count as distinct, so ordinary agendas are unaffected.
#[ORM\UniqueConstraint(name: 'uniq_agenda_user_module', columns: ['user_id', 'module'])]
#[ApiFilter(OrderFilter::class, properties: ['id', 'name'])]
#[Indexed(index: 'agendas', module: 'calendar')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateAgendaProcessor::class),
    new Patch(processor: UpdateAgendaProcessor::class),
    new Delete(processor: DeleteAgendaProcessor::class),
])]
class Agenda implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use HasGoogleCalendarSyncTrait;
    use MercurePayloadFilterTrait;
    /** The agenda where the meal planner files what it produces (MAG-324). */
    public const MODULE_COOKBOOK = 'cookbook';

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    // keyword: the admin sorts agendas by name, and Elasticsearch cannot sort
    // on an analysed text field — only on its keyword sub-field.
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $name;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $description = null;

    #[ORM\Column(length: 50, options: ['default' => 'Europe/Paris'])]
    private string $timeZone = 'Europe/Paris';

    #[ORM\Column(length: 7, nullable: true)]
    private ?string $color = null;

    #[ORM\Column(options: ['default' => false])]
    private bool $isDefault = false;

    // Set by the module that owns the agenda, never by a client: it is what
    // recognises « the meals' agenda », whatever the user renamed it to. A module
    // agenda is internal — never synced with Google, never the default one, and
    // never offered for an ordinary event (MAG-324).
    #[ORM\Column(length: 50, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(writable: false)]
    private ?string $module = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

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

    public function getModule(): ?string
    {
        return $this->module;
    }

    public function setModule(?string $module): static
    {
        $this->module = $module;

        return $this;
    }

    public function isModule(): bool
    {
        return null !== $this->module;
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

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'description' => $this->description,
            'color' => $this->color,
            'timeZone' => $this->timeZone,
            'isDefault' => $this->isDefault,
            'module' => $this->module,
            // The agenda collection is served from Elasticsearch, and the admin
            // hides a Google calendar that is already connected by reading this
            // field — without it the guard let the same calendar in twice
            // (MAG-148).
            'googleCalendarId' => $this->googleCalendarId,
            'userId' => (string) $this->user->getId(),
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'color' => $this->color,
            'isDefault' => $this->isDefault,
            'module' => $this->module,
        ], $changedProperties);
    }
}
