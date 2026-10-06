<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\State\CreateEventProcessor;
use Maggie\Calendar\State\DeleteEventProcessor;
use Maggie\Calendar\State\UpdateEventProcessor;
use Maggie\Calendar\Trait\HasGoogleEventTrackingTrait;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: EventRepository::class)]
#[ORM\InheritanceType('JOINED')]
#[ORM\DiscriminatorColumn(name: 'dtype', type: 'string', length: 20)]
#[ORM\DiscriminatorMap(['event' => Event::class])]
#[ORM\Index(columns: ['start_at', 'end_at'], name: 'idx_event_dates')]
#[ORM\Index(columns: ['status'], name: 'idx_event_status')]
#[ORM\UniqueConstraint(name: 'uniq_google_event_agenda', columns: ['google_event_id', 'agenda_id'])]
#[ApiFilter(DateFilter::class, properties: ['startAt', 'endAt'])]
#[ApiFilter(ExistsFilter::class, properties: ['rrule'])]
#[ApiFilter(OrderFilter::class, properties: ['id', 'startAt'])]
#[Indexed(index: 'events', module: 'calendar')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateEventProcessor::class),
    new Patch(processor: UpdateEventProcessor::class),
    new Delete(processor: DeleteEventProcessor::class),
])]
class Event implements MercurePublishable, OwnedThroughInterface, IndexableInterface
{
    use HasGoogleEventTrackingTrait;
    use MercurePayloadFilterTrait;

    /** The column's default, and what a time zone nobody can resolve falls back on. */
    public const FALLBACK_TIME_ZONE = 'Europe/Paris';

    public static function getOwnerRelation(): string
    {
        return 'agenda';
    }

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0)]
    private string $summary;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $description = null;

    #[ORM\Column(length: 500, nullable: true)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $location = null;

    #[ORM\Column(options: ['default' => false])]
    #[IndexedField(type: 'boolean')]
    private bool $allDay = false;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[Assert\NotNull]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $startAt;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[Assert\NotNull]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $endAt;

    #[ORM\Column(length: 50, options: ['default' => self::FALLBACK_TIME_ZONE])]
    #[Assert\Timezone]
    private string $timeZone = self::FALLBACK_TIME_ZONE;

    /** @var string|null RFC 5545 RRULE (e.g. "FREQ=WEEKLY;INTERVAL=2") */
    #[ORM\Column(length: 500, nullable: true)]
    #[IndexedField(type: 'keyword')]
    private ?string $rrule = null;

    /** For exception instances: links to the parent recurring event */
    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[IndexedRelation(targetEntity: self::class, sourceField: 'recurringEventId')]
    private ?self $recurringEvent = null;

    /** Which occurrence this exception replaces */
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $originalStartAt = null;

    #[ORM\Column(length: 20, enumType: EventStatus::class, options: ['default' => 'confirmed'])]
    #[IndexedField(type: 'keyword')]
    private EventStatus $status = EventStatus::Confirmed;

    /**
     * Reminders as JSON: {useDefault: bool, overrides: [{method: "popup"|"email", minutes: int}]}
     *
     * @var array<string, mixed>|null
     */
    #[ORM\Column(type: Types::JSON, nullable: true)]
    private ?array $reminders = null;

    #[ORM\ManyToOne(targetEntity: Agenda::class, inversedBy: 'events')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Agenda::class, sourceField: 'agendaId')]
    private Agenda $agenda;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getSummary(): string
    {
        return $this->summary;
    }

    public function setSummary(string $summary): static
    {
        $this->summary = $summary;

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

    public function getLocation(): ?string
    {
        return $this->location;
    }

    public function setLocation(?string $location): static
    {
        $this->location = $location;

        return $this;
    }

    public function isAllDay(): bool
    {
        return $this->allDay;
    }

    public function setAllDay(bool $allDay): static
    {
        $this->allDay = $allDay;

        return $this;
    }

    public function getStartAt(): \DateTimeImmutable
    {
        return $this->startAt;
    }

    public function setStartAt(\DateTimeImmutable $startAt): static
    {
        $this->startAt = $startAt;

        return $this;
    }

    public function getEndAt(): \DateTimeImmutable
    {
        return $this->endAt;
    }

    public function setEndAt(\DateTimeImmutable $endAt): static
    {
        $this->endAt = $endAt;

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

    public function getRrule(): ?string
    {
        return $this->rrule;
    }

    public function setRrule(?string $rrule): static
    {
        $this->rrule = $rrule;

        return $this;
    }

    public function getRecurringEvent(): ?self
    {
        return $this->recurringEvent;
    }

    public function setRecurringEvent(?self $recurringEvent): static
    {
        $this->recurringEvent = $recurringEvent;

        return $this;
    }

    public function getOriginalStartAt(): ?\DateTimeImmutable
    {
        return $this->originalStartAt;
    }

    public function setOriginalStartAt(?\DateTimeImmutable $originalStartAt): static
    {
        $this->originalStartAt = $originalStartAt;

        return $this;
    }

    public function getStatus(): EventStatus
    {
        return $this->status;
    }

    public function setStatus(EventStatus $status): static
    {
        $this->status = $status;

        return $this;
    }

    /** @return array<string, mixed>|null */
    public function getReminders(): ?array
    {
        return $this->reminders;
    }

    /** @param array<string, mixed>|null $reminders */
    public function setReminders(?array $reminders): static
    {
        $this->reminders = $reminders;

        return $this;
    }

    public function getAgenda(): Agenda
    {
        return $this->agenda;
    }

    public function setAgenda(Agenda $agenda): static
    {
        $this->agenda = $agenda;

        return $this;
    }

    public function isRecurring(): bool
    {
        return null !== $this->rrule;
    }

    public function isException(): bool
    {
        return null !== $this->recurringEvent;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'summary' => $this->summary,
            'description' => $this->description,
            'location' => $this->location,
            'allDay' => $this->allDay,
            'startAt' => $this->startAt->format('c'),
            'endAt' => $this->endAt->format('c'),
            'timeZone' => $this->timeZone,
            'rrule' => $this->rrule,
            'recurringEventId' => null !== $this->recurringEvent ? (string) $this->recurringEvent->getId() : null,
            'originalStartAt' => $this->originalStartAt?->format('c'),
            'status' => $this->status->value,
            'reminders' => $this->reminders,
            'agendaId' => (string) $this->agenda->getId(),
            'userId' => (string) $this->agenda->getUser()->getId(),
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'summary' => $this->summary,
            'startAt' => $this->startAt->format('c'),
            'endAt' => $this->endAt->format('c'),
        ], $changedProperties);
    }
}
