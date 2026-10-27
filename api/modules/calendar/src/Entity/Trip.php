<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Filter\TripDayFilter;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedThroughInterface;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

/**
 * The trip Maggie chose to an event (« Partir à l'heure », MAG-654): at most
 * one per event, removed with it ({@see \Maggie\Calendar\Doctrine\TripCleanupListener}).
 * Written by `set_trip` only, read by `list_trips`, `/api/trips` and the screens.
 *
 * `departAt` is when to leave — a new value is a new departure alert. The
 * origin is copied, not referenced: « ma position » moves, the trip keeps where
 * it was computed from. `originDecidedBy` user means the user chose it: it is
 * kept, only the durations are computed again.
 *
 * Owned through its event. Served by Doctrine: not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: TripRepository::class)]
#[ORM\Index(name: 'idx_trip_depart_at', columns: ['depart_at'])]
#[ApiFilter(TripDayFilter::class, properties: ['date'])]
#[ApiResource(operations: [
    new GetCollection(order: ['departAt' => 'ASC']),
    new Get(),
])]
class Trip implements MercurePublishable, OwnedThroughInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\OneToOne(targetEntity: Event::class)]
    #[ORM\JoinColumn(nullable: false, unique: true, onDelete: 'CASCADE')]
    private Event $event;

    #[ORM\Column(length: 10, enumType: TripMode::class)]
    private TripMode $mode;

    /** @var list<array{kind: string, minutes: int, label?: string}> */
    #[ORM\Column(type: Types::JSON)]
    private array $steps = [];

    #[ORM\Column]
    private int $durationMinutes;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $departAt;

    #[ORM\Column(type: Types::TEXT)]
    private string $navigationUrl;

    /** `position`, `place` or `event`, as {@see \Maggie\Calendar\Service\TripOrigin::$kind}. */
    #[ORM\Column(length: 10)]
    private string $originKind;

    /** The place or the event the trip starts from; null from the position. */
    #[ORM\Column(type: 'ulid', nullable: true)]
    private ?Ulid $originRef = null;

    #[ORM\Column(length: 255)]
    private string $originLabel;

    #[ORM\Column]
    private float $originLatitude;

    #[ORM\Column]
    private float $originLongitude;

    /** Where the car was taken from, by car only. */
    #[ORM\Column(nullable: true)]
    private ?float $carLatitude = null;

    #[ORM\Column(nullable: true)]
    private ?float $carLongitude = null;

    #[ORM\Column(length: 10, enumType: TripDecider::class)]
    private TripDecider $originDecidedBy;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $computedAt;

    public function __construct(Event $event)
    {
        $this->id = new Ulid();
        $this->event = $event;
    }

    #[Ignore]
    public static function getOwnerRelation(): string
    {
        return 'event';
    }

    /** Replaces everything the trip was computed with. */
    public function apply(TripEstimate $estimate, TripDecider $decidedBy, \DateTimeImmutable $computedAt): static
    {
        $this->mode = $estimate->mode;
        $this->steps = $estimate->toArray()['steps'];
        $this->durationMinutes = $estimate->durationMinutes;
        $this->departAt = $estimate->departAt;
        $this->navigationUrl = $estimate->navigationUrl;
        $this->originKind = $estimate->origin->kind;
        $this->originRef = null === $estimate->origin->id ? null : Ulid::fromString($estimate->origin->id);
        $this->originLabel = $estimate->origin->label;
        $this->originLatitude = $estimate->origin->latitude;
        $this->originLongitude = $estimate->origin->longitude;
        $this->carLatitude = $estimate->carLatitude;
        $this->carLongitude = $estimate->carLongitude;
        $this->originDecidedBy = $decidedBy;
        $this->computedAt = $computedAt;

        return $this;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getEvent(): Event
    {
        return $this->event;
    }

    public function getMode(): TripMode
    {
        return $this->mode;
    }

    /** @return list<array{kind: string, minutes: int, label?: string}> */
    public function getSteps(): array
    {
        return $this->steps;
    }

    public function getDurationMinutes(): int
    {
        return $this->durationMinutes;
    }

    public function getDepartAt(): \DateTimeImmutable
    {
        return $this->departAt;
    }

    public function getNavigationUrl(): string
    {
        return $this->navigationUrl;
    }

    public function getOriginKind(): string
    {
        return $this->originKind;
    }

    public function getOriginRef(): ?string
    {
        return $this->originRef?->toBase32();
    }

    public function getOriginLabel(): string
    {
        return $this->originLabel;
    }

    public function getOriginLatitude(): float
    {
        return $this->originLatitude;
    }

    public function getOriginLongitude(): float
    {
        return $this->originLongitude;
    }

    public function getCarLatitude(): ?float
    {
        return $this->carLatitude;
    }

    public function getCarLongitude(): ?float
    {
        return $this->carLongitude;
    }

    public function getOriginDecidedBy(): TripDecider
    {
        return $this->originDecidedBy;
    }

    public function getComputedAt(): \DateTimeImmutable
    {
        return $this->computedAt;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'event' => '/api/events/'.$this->event->getId(),
            'mode' => $this->mode->value,
            'steps' => $this->steps,
            'durationMinutes' => $this->durationMinutes,
            'departAt' => $this->departAt->format(\DATE_ATOM),
            'navigationUrl' => $this->navigationUrl,
            'originKind' => $this->originKind,
            'originRef' => $this->getOriginRef(),
            'originLabel' => $this->originLabel,
            'originLatitude' => $this->originLatitude,
            'originLongitude' => $this->originLongitude,
            'carLatitude' => $this->carLatitude,
            'carLongitude' => $this->carLongitude,
            'originDecidedBy' => $this->originDecidedBy->value,
            'computedAt' => $this->computedAt->format(\DATE_ATOM),
        ], $changedProperties);
    }
}
