<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Filter\EventPeriodFilter;
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
use Maggie\Core\Serializer\StrictDayNormalizer;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

#[ORM\Entity(repositoryClass: EventRepository::class)]
#[ORM\InheritanceType('JOINED')]
#[ORM\DiscriminatorColumn(name: 'dtype', type: 'string', length: 20)]
#[ORM\DiscriminatorMap(['event' => Event::class])]
#[ORM\Index(columns: ['start_at', 'end_at'], name: 'idx_event_dates')]
#[ORM\Index(columns: ['start_date', 'end_date'], name: 'idx_event_days')]
#[ORM\Index(columns: ['status'], name: 'idx_event_status')]
#[ORM\UniqueConstraint(name: 'uniq_google_event_agenda', columns: ['google_event_id', 'agenda_id'])]
#[ApiFilter(EventPeriodFilter::class, properties: ['startAt', 'endAt'])]
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

    /** As many reminders as Google accepts on one event. */
    public const int MAX_REMINDERS = 5;

    /** Four weeks before the start, Google's own ceiling. */
    public const int MAX_REMINDER_MINUTES = 40320;

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

    // A timed event has instants, an all-day one has days — never both (MAG-382).
    //
    // An all-day event is a pair of dates, the last one included: the 1st of
    // January alone is `startDate = endDate = 2037-01-01`. No time, no zone,
    // so no reader can push it onto the day next door the way an instant at
    // midnight did — read in Paris, the old `00:00Z → 00:00Z` of the next day
    // ended at 01:00 on the 2nd and showed on two days. `startAt` and `endAt`
    // are null on one, `startDate` and `endDate` null on a timed event, and
    // `validateSchedule()` refuses a mix.
    //
    // Comments and not docblocks: API Platform publishes a property's docblock
    // as its description.
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', dayField: 'startDate')]
    #[ApiProperty(description: 'When a timed event starts. Null on an all-day event, which has startDate instead.')]
    private ?\DateTimeImmutable $startAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', dayField: 'endDate')]
    #[ApiProperty(description: 'When a timed event ends. Null on an all-day event, which has endDate instead.')]
    private ?\DateTimeImmutable $endAt = null;

    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The first day of an all-day event, YYYY-MM-DD. Null on a timed event.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $startDate = null;

    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The last day of an all-day event, YYYY-MM-DD, included: a one-day event has endDate = startDate, which is what an absent endDate becomes. Null on a timed event.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $endDate = null;

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
     * Google's shape, and the only one anything reads: `CheckRemindersCommand`
     * looks under `overrides`, so a bare list is no reminder at all and nothing
     * says so — the e2e seed shipped that shape for a while and the whole chain
     * was silently dead. The web, the mobile app and Maggie all write this field
     * now (MAG-121), so it is checked on the way in rather than discovered by a
     * reminder nobody received.
     *
     * @var array<string, mixed>|null
     */
    #[ORM\Column(type: Types::JSON, nullable: true)]
    // The constraints below let API Platform describe the keys, but they also make
    // it drop the null — and null is how a client says "no reminder left".
    #[ApiProperty(openapiContext: ['type' => ['object', 'null']])]
    #[Assert\Collection(fields: [
        'useDefault' => new Assert\Optional([new Assert\Type('bool')]),
        'overrides' => new Assert\Optional([new Assert\Sequentially([
            new Assert\Type('array'),
            new Assert\Count(max: self::MAX_REMINDERS),
            new Assert\All([new Assert\Sequentially([
                new Assert\Type('array'),
                new Assert\Collection(fields: [
                    // `sms` only to let an old imported row through; nothing of ours writes it.
                    'method' => new Assert\Required([new Assert\Choice(choices: ['popup', 'email', 'sms'])]),
                    'minutes' => new Assert\Required([
                        new Assert\Type('int'),
                        // Zero only because Google writes it for "au moment de l'événement",
                        // and the validator reads the whole stored value at every PATCH:
                        // refusing it would make an imported event uneditable. The cron
                        // skips it and `EventReminders` refuses it, so we never promise one.
                        new Assert\Range(min: 0, max: self::MAX_REMINDER_MINUTES),
                    ]),
                ]),
            ])]),
        ])]),
    ])]
    private ?array $reminders = null;

    #[ORM\ManyToOne(targetEntity: Agenda::class, inversedBy: 'events')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    // A meal is filed by the server, so the body of one has no agenda to check (MAG-324).
    #[Assert\When(expression: '!this.isAgendaChosenByServer()', constraints: [new Assert\NotNull()])]
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

    public function getStartAt(): ?\DateTimeImmutable
    {
        return $this->startAt;
    }

    public function setStartAt(?\DateTimeImmutable $startAt): static
    {
        $this->startAt = $startAt;

        return $this;
    }

    public function getEndAt(): ?\DateTimeImmutable
    {
        return $this->endAt;
    }

    public function setEndAt(?\DateTimeImmutable $endAt): static
    {
        $this->endAt = $endAt;

        return $this;
    }

    public function getStartDate(): ?\DateTimeImmutable
    {
        return $this->startDate;
    }

    public function setStartDate(?\DateTimeImmutable $startDate): static
    {
        $this->startDate = self::bareDay($startDate);

        return $this;
    }

    public function getEndDate(): ?\DateTimeImmutable
    {
        return $this->endDate;
    }

    public function setEndDate(?\DateTimeImmutable $endDate): static
    {
        $this->endDate = self::bareDay($endDate);

        return $this;
    }

    /**
     * Makes the event an all-day one, from its first day to its last, included.
     *
     * The instants go: an all-day event has none. No last day is one day long.
     */
    public function scheduleAllDay(\DateTimeImmutable $startDate, ?\DateTimeImmutable $endDate = null): static
    {
        $this->setStartAt(null);
        $this->setEndAt(null);
        $this->setStartDate($startDate);
        $this->setEndDate($endDate ?? $startDate);

        return $this->setAllDay(true);
    }

    /** Makes the event a timed one; the days go. */
    public function scheduleTimed(\DateTimeImmutable $startAt, \DateTimeImmutable $endAt): static
    {
        $this->setStartDate(null);
        $this->setEndDate(null);
        $this->setStartAt($startAt);
        $this->setEndAt($endAt);

        return $this->setAllDay(false);
    }

    /**
     * The instant the event starts, where one cannot be avoided: a reminder
     * fires at one, and a list mixing both kinds is sorted by one.
     *
     * An all-day event starts at midnight of its first day in its own zone.
     * Never stored, never sent: the event itself is a day.
     */
    #[Ignore]
    public function getStartInstant(): \DateTimeImmutable
    {
        if (null !== $this->startAt) {
            return $this->startAt;
        }

        return new \DateTimeImmutable(($this->startDate ?? new \DateTimeImmutable('today'))->format('Y-m-d'), $this->zone());
    }

    /** Same as {@see getStartInstant()}: an all-day event ends at the midnight after its last day. */
    #[Ignore]
    public function getEndInstant(): \DateTimeImmutable
    {
        if (null !== $this->endAt) {
            return $this->endAt;
        }

        $last = $this->endDate ?? $this->startDate ?? new \DateTimeImmutable('today');

        return (new \DateTimeImmutable($last->format('Y-m-d'), $this->zone()))->modify('+1 day');
    }

    /** One schedule or the other, whole — a 400 otherwise (MAG-382). */
    #[Assert\Callback]
    public function validateSchedule(ExecutionContextInterface $context): void
    {
        if ($this->allDay) {
            if (null === $this->startDate) {
                $context->buildViolation('An all-day event needs a startDate (YYYY-MM-DD).')->atPath('startDate')->addViolation();
            }
            foreach (['startAt' => $this->startAt, 'endAt' => $this->endAt] as $path => $instant) {
                if (null !== $instant) {
                    $context->buildViolation('An all-day event has no {{ field }}: send startDate and endDate, and {{ field }} null.')
                        ->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
                }
            }
            if (null !== $this->startDate && null !== $this->endDate && $this->endDate < $this->startDate) {
                $context->buildViolation('endDate is the last day, included: it cannot be before startDate.')->atPath('endDate')->addViolation();
            }

            return;
        }

        foreach (['startAt' => $this->startAt, 'endAt' => $this->endAt] as $path => $instant) {
            if (null === $instant) {
                $context->buildViolation('A timed event needs {{ field }}.')->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
            }
        }
        foreach (['startDate' => $this->startDate, 'endDate' => $this->endDate] as $path => $day) {
            if (null !== $day) {
                $context->buildViolation('A timed event has no {{ field }}: send allDay true for a day, or {{ field }} null.')
                    ->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
            }
        }
    }

    /** A day carries no time and no zone: whatever came in, only its date is kept. */
    private static function bareDay(?\DateTimeImmutable $day): ?\DateTimeImmutable
    {
        return null === $day ? null : new \DateTimeImmutable($day->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    private function zone(): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->timeZone);
        } catch (\Exception) {
            return new \DateTimeZone(self::FALLBACK_TIME_ZONE);
        }
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

    /** Whether the server picks the agenda itself, so a client body need not carry one. */
    #[Ignore]
    public function isAgendaChosenByServer(): bool
    {
        return false;
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
            'startAt' => $this->startAt?->format('c'),
            'endAt' => $this->endAt?->format('c'),
            'startDate' => $this->startDate?->format('Y-m-d'),
            'endDate' => $this->endDate?->format('Y-m-d'),
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
            'allDay' => $this->allDay,
            'startAt' => $this->startAt?->format('c'),
            'endAt' => $this->endAt?->format('c'),
            'startDate' => $this->startDate?->format('Y-m-d'),
            'endDate' => $this->endDate?->format('Y-m-d'),
        ], $changedProperties);
    }
}
