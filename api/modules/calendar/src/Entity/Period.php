<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
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
use Maggie\Calendar\Filter\EventExpandFilter;
use Maggie\Calendar\Filter\EventPeriodFilter;
use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Calendar\State\CreatePeriodProcessor;
use Maggie\Calendar\State\DeletePeriodProcessor;
use Maggie\Calendar\State\PeriodCollectionProvider;
use Maggie\Calendar\State\UpdatePeriodProcessor;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Serializer\StrictDayNormalizer;
use Recurr\Rule;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// A period of the owner's life (MAG-809): the custody of his son, holidays, a
// trip. Maggie's own, never synced with Google, and it holds
// no slot: it says what kind of days these are. Its kind is read in its name —
// « Garde … », « Vacances … » — so there is no type field. The agenda gives
// the colour and the owner.
//
// The schedule is an event's, field for field: instants or days, the end day
// excluded (MAG-382), and an RRULE.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: PeriodRepository::class)]
#[ORM\Index(columns: ['start_at', 'end_at'], name: 'idx_period_dates')]
#[ORM\Index(columns: ['start_date', 'end_date'], name: 'idx_period_days')]
#[ApiFilter(EventPeriodFilter::class, properties: ['startAt', 'endAt'])]
#[ApiFilter(ExistsFilter::class, properties: ['rrule'])]
#[Indexed(index: 'periods', module: 'calendar')]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new GetCollection(provider: PeriodCollectionProvider::class, filters: [EventExpandFilter::class]),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreatePeriodProcessor::class),
    new Patch(processor: UpdatePeriodProcessor::class),
    new Delete(processor: DeletePeriodProcessor::class),
])]
class Period implements MercurePublishable, OwnedThroughInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    /** The collection path, and the Mercure topic the clients subscribe to. */
    public const COLLECTION = '/api/periods';

    public static function getOwnerRelation(): string
    {
        return 'agenda';
    }

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[Assert\Length(max: 255)]
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\ManyToOne(targetEntity: Agenda::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Assert\NotNull]
    #[IndexedRelation(targetEntity: Agenda::class, sourceField: 'agendaId')]
    private Agenda $agenda;

    #[ORM\Column(options: ['default' => false])]
    #[IndexedField(type: 'boolean')]
    private bool $allDay = false;

    // Instants or days, never both, as on an event (MAG-382): an all-day period
    // is `[startDate, endDate)`, the end excluded, a timed one `[startAt, endAt)`.
    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', dayField: 'startDate')]
    #[ApiProperty(description: 'When a timed period starts. Null on an all-day period, which has startDate instead.')]
    private ?\DateTimeImmutable $startAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', dayField: 'endDate', dayFieldIsExclusiveEnd: true)]
    #[ApiProperty(description: 'When a timed period ends. Null on an all-day period, which has endDate instead.')]
    private ?\DateTimeImmutable $endAt = null;

    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The first day of an all-day period, YYYY-MM-DD. Null on a timed period.',
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
        description: 'The day after the last day of an all-day period, YYYY-MM-DD, excluded — as an event\'s: a period on 2037-01-01 alone has endDate 2037-01-02. Null on a timed period.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-02'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $endDate = null;

    #[ORM\Column(length: 50, options: ['default' => Event::FALLBACK_TIME_ZONE])]
    #[Assert\Timezone]
    private string $timeZone = Event::FALLBACK_TIME_ZONE;

    /** @var string|null RFC 5545 RRULE (e.g. "FREQ=WEEKLY;INTERVAL=2") */
    #[ORM\Column(length: 500, nullable: true)]
    #[IndexedField(type: 'keyword')]
    private ?string $rrule = null;

    public function __construct()
    {
        $this->id = new Ulid();
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

    public function getAgenda(): Agenda
    {
        return $this->agenda;
    }

    public function setAgenda(Agenda $agenda): static
    {
        $this->agenda = $agenda;

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

    /** Makes the period an all-day one, from its first day to its end, excluded; no end is one day. */
    public function scheduleAllDay(\DateTimeImmutable $startDate, ?\DateTimeImmutable $endDate = null): static
    {
        $this->setStartAt(null);
        $this->setEndAt(null);
        $this->setStartDate($startDate);
        $this->setEndDate($endDate ?? self::bareDay($startDate)?->modify('+1 day'));

        return $this->setAllDay(true);
    }

    /** Makes the period a timed one; the days go. */
    public function scheduleTimed(\DateTimeImmutable $startAt, \DateTimeImmutable $endAt): static
    {
        $this->setStartDate(null);
        $this->setEndDate(null);
        $this->setStartAt($startAt);
        $this->setEndAt($endAt);

        return $this->setAllDay(false);
    }

    /** The instant the period starts: midnight of its first day in its zone for an all-day one. Never sent. */
    #[Ignore]
    public function getStartInstant(): \DateTimeImmutable
    {
        if (null !== $this->startAt) {
            return $this->startAt;
        }

        return new \DateTimeImmutable(($this->startDate ?? new \DateTimeImmutable('today'))->format('Y-m-d'), $this->zone());
    }

    /** Same as {@see getStartInstant()}: an all-day period ends at midnight of its end date. */
    #[Ignore]
    public function getEndInstant(): \DateTimeImmutable
    {
        if (null !== $this->endAt) {
            return $this->endAt;
        }
        if (null !== $this->endDate) {
            return new \DateTimeImmutable($this->endDate->format('Y-m-d'), $this->zone());
        }

        return $this->getStartInstant()->modify('+1 day');
    }

    /** An RRULE the expansion can read: a broken one would fail every read of the range. */
    #[Assert\Callback]
    public function validateRrule(ExecutionContextInterface $context): void
    {
        if (null === $this->rrule) {
            return;
        }

        try {
            new Rule($this->rrule, $this->getStartInstant());
        } catch (\Throwable) {
            $context->buildViolation('rrule is an RFC 5545 rule without the RRULE: prefix, e.g. FREQ=WEEKLY;INTERVAL=2.')->atPath('rrule')->addViolation();
        }
    }

    /** One schedule or the other, whole, and an end after the start — a 422 otherwise. */
    #[Assert\Callback]
    public function validateSchedule(ExecutionContextInterface $context): void
    {
        if ($this->allDay) {
            if (null === $this->startDate) {
                $context->buildViolation('An all-day period needs a startDate (YYYY-MM-DD).')->atPath('startDate')->addViolation();
            }
            foreach (['startAt' => $this->startAt, 'endAt' => $this->endAt] as $path => $instant) {
                if (null !== $instant) {
                    $context->buildViolation('An all-day period has no {{ field }}: send startDate and endDate, and {{ field }} null.')
                        ->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
                }
            }
            if (null !== $this->startDate && null !== $this->endDate && $this->endDate <= $this->startDate) {
                $context->buildViolation('endDate is the day after the last one, excluded: it must be after startDate.')->atPath('endDate')->addViolation();
            }

            return;
        }

        foreach (['startAt' => $this->startAt, 'endAt' => $this->endAt] as $path => $instant) {
            if (null === $instant) {
                $context->buildViolation('A timed period needs {{ field }}.')->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
            }
        }
        foreach (['startDate' => $this->startDate, 'endDate' => $this->endDate] as $path => $day) {
            if (null !== $day) {
                $context->buildViolation('A timed period has no {{ field }}: send allDay true for days, or {{ field }} null.')
                    ->setParameter('{{ field }}', $path)->atPath($path)->addViolation();
            }
        }
        if (null !== $this->startAt && null !== $this->endAt && $this->endAt <= $this->startAt) {
            $context->buildViolation('endAt must be after startAt.')->atPath('endAt')->addViolation();
        }
    }

    /** A day carries no time and no zone: whatever came in, only its date is kept. */
    private static function bareDay(?\DateTimeImmutable $day): ?\DateTimeImmutable
    {
        return null === $day ? null : new \DateTimeImmutable($day->format('Y-m-d'), new \DateTimeZone('UTC'));
    }

    /** The zone the period is read in: its own, or the default one when nobody can resolve it. */
    #[Ignore]
    public function zone(): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->timeZone);
        } catch (\Exception) {
            return new \DateTimeZone(Event::FALLBACK_TIME_ZONE);
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

    public function isRecurring(): bool
    {
        return null !== $this->rrule;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'allDay' => $this->allDay,
            'startAt' => $this->startAt?->format('c'),
            'endAt' => $this->endAt?->format('c'),
            'startDate' => $this->startDate?->format('Y-m-d'),
            'endDate' => $this->endDate?->format('Y-m-d'),
            'timeZone' => $this->timeZone,
            'rrule' => $this->rrule,
            'agendaId' => (string) $this->agenda->getId(),
            'userId' => (string) $this->agenda->getUser()->getId(),
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'allDay' => $this->allDay,
            'startAt' => $this->startAt?->format('c'),
            'endAt' => $this->endAt?->format('c'),
            'startDate' => $this->startDate?->format('Y-m-d'),
            'endDate' => $this->endDate?->format('Y-m-d'),
            'timeZone' => $this->timeZone,
            'rrule' => $this->rrule,
        ], $changedProperties);
    }
}
