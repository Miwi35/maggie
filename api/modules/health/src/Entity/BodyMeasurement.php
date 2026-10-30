<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
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
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\State\CreateBodyMeasurementProcessor;
use Maggie\Health\State\DeleteBodyMeasurementProcessor;
use Maggie\Health\State\UpdateBodyMeasurementProcessor;
use Symfony\Bridge\Doctrine\Validator\Constraints\UniqueEntity;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// A weighing, a body fat reading or a waist size, alone or together (MAG-706).
// Served by Doctrine, like Workout: the list is a period of one user, nothing
// is searched. `externalId` de-duplicates an import (Health Connect, Withings):
// unique per user, null allowed many times. An `excluded` measurement stays
// listed but counts in no figure (a scale shared with someone else).
//
// The three figures are DECIMAL columns, which Doctrine reads as strings: the
// mapped properties carry other names (`weight`, `bodyFat`, `waist`) so that
// the API reads and writes `weightKg`, `bodyFatPercent` and `waistCm` through
// the float accessors — JSON numbers, never strings.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: BodyMeasurementRepository::class)]
#[ORM\Index(name: 'idx_body_measurement_user_measured_at', columns: ['user_id', 'measured_at'])]
#[ORM\UniqueConstraint(name: 'uniq_body_measurement_user_external', columns: ['user_id', 'external_id'])]
#[UniqueEntity(fields: ['user', 'externalId'], message: 'This externalId is already used by another measurement.', ignoreNull: 'externalId')]
#[ApiFilter(DateFilter::class, properties: ['measuredAt'])]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/body-measurements', order: ['measuredAt' => 'DESC'], paginationMaximumItemsPerPage: 1000),
    new Get(uriTemplate: '/body-measurements/{id}'),
    new Post(uriTemplate: '/body-measurements', processor: CreateBodyMeasurementProcessor::class),
    new Patch(uriTemplate: '/body-measurements/{id}', processor: UpdateBodyMeasurementProcessor::class),
    new Delete(uriTemplate: '/body-measurements/{id}', processor: DeleteBodyMeasurementProcessor::class),
])]
class BodyMeasurement implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const COLLECTION = '/api/body-measurements';

    /** {@see Workout::NO_OFFSET_ZONE}: a time without offset is refused, never read in the server's zone. */
    private const NO_OFFSET_ZONE = 'Pacific/Kiritimati';

    public const MAX_WEIGHT_KG = 500;

    public const MAX_BODY_FAT_PERCENT = 100;

    public const MAX_WAIST_CM = 300;

    /** Doctrine property → payload key, for {@see self::filterPayload()}. */
    private const PAYLOAD_KEYS = ['weight' => 'weightKg', 'bodyFat' => 'bodyFatPercent', 'waist' => 'waistCm'];

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[Assert\NotNull]
    #[Context(denormalizationContext: [DateTimeNormalizer::TIMEZONE_KEY => self::NO_OFFSET_ZONE])]
    #[ApiProperty(description: 'When the measurement was taken: ISO 8601 with offset, e.g. 2026-10-21T07:12:00+02:00.')]
    private \DateTimeImmutable $measuredAt;

    #[ORM\Column(name: 'weight_kg', type: Types::DECIMAL, precision: 5, scale: 2, nullable: true)]
    #[ApiProperty(readable: false, writable: false)]
    private ?string $weight = null;

    #[ORM\Column(name: 'body_fat_percent', type: Types::DECIMAL, precision: 4, scale: 1, nullable: true)]
    #[ApiProperty(readable: false, writable: false)]
    private ?string $bodyFat = null;

    #[ORM\Column(name: 'waist_cm', type: Types::DECIMAL, precision: 5, scale: 1, nullable: true)]
    #[ApiProperty(readable: false, writable: false)]
    private ?string $waist = null;

    #[ORM\Column(length: 16, enumType: MeasurementSource::class)]
    #[ApiProperty(description: 'manual, health_connect or withings.')]
    private MeasurementSource $source = MeasurementSource::Manual;

    #[ORM\Column(length: 255, nullable: true)]
    #[Assert\Length(max: 255)]
    #[ApiProperty(description: 'The identifier of the measurement in the system it comes from; unique per user, it de-duplicates an import.')]
    private ?string $externalId = null;

    #[ORM\Column(type: 'boolean', options: ['default' => false])]
    #[ApiProperty(description: 'Set aside: still listed, never counted (the day\'s weight, the trend).')]
    private bool $excluded = false;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validate(ExecutionContextInterface $context): void
    {
        if (isset($this->measuredAt) && self::NO_OFFSET_ZONE === $this->measuredAt->getTimezone()->getName()) {
            $context->buildViolation('measuredAt must have a time and an offset, e.g. 2026-10-21T07:12:00+02:00.')->atPath('measuredAt')->addViolation();
        }
        if (null === $this->weight && null === $this->bodyFat && null === $this->waist) {
            $context->buildViolation('Give at least one of weightKg, bodyFatPercent or waistCm.')->atPath('weightKg')->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getMeasuredAt(): \DateTimeImmutable
    {
        return $this->measuredAt;
    }

    public function setMeasuredAt(\DateTimeImmutable $measuredAt): static
    {
        $this->measuredAt = $measuredAt;

        return $this;
    }

    #[Assert\Positive]
    #[Assert\LessThanOrEqual(self::MAX_WEIGHT_KG)]
    #[ApiProperty(description: 'Weight in kilograms, e.g. 72.4 (more than 0, at most 500).')]
    public function getWeightKg(): ?float
    {
        return null === $this->weight ? null : (float) $this->weight;
    }

    public function setWeightKg(?float $weightKg): static
    {
        $this->weight = self::decimal($weightKg, 2);

        return $this;
    }

    #[Assert\Positive]
    #[Assert\LessThan(self::MAX_BODY_FAT_PERCENT)]
    #[ApiProperty(description: 'Body fat in percent, e.g. 21.3 (more than 0, less than 100).')]
    public function getBodyFatPercent(): ?float
    {
        return null === $this->bodyFat ? null : (float) $this->bodyFat;
    }

    public function setBodyFatPercent(?float $bodyFatPercent): static
    {
        $this->bodyFat = self::decimal($bodyFatPercent, 1);

        return $this;
    }

    #[Assert\Positive]
    #[Assert\LessThanOrEqual(self::MAX_WAIST_CM)]
    #[ApiProperty(description: 'Waist size in centimetres, e.g. 84.5 (more than 0, at most 300).')]
    public function getWaistCm(): ?float
    {
        return null === $this->waist ? null : (float) $this->waist;
    }

    public function setWaistCm(?float $waistCm): static
    {
        $this->waist = self::decimal($waistCm, 1);

        return $this;
    }

    public function getSource(): MeasurementSource
    {
        return $this->source;
    }

    public function setSource(MeasurementSource $source): static
    {
        $this->source = $source;

        return $this;
    }

    public function getExternalId(): ?string
    {
        return $this->externalId;
    }

    public function setExternalId(?string $externalId): static
    {
        $this->externalId = $externalId;

        return $this;
    }

    public function isExcluded(): bool
    {
        return $this->excluded;
    }

    public function setExcluded(bool $excluded): static
    {
        $this->excluded = $excluded;

        return $this;
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

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'id' => (string) $this->id,
            'measuredAt' => $this->measuredAt->format(\DATE_ATOM),
            'weightKg' => $this->getWeightKg(),
            'bodyFatPercent' => $this->getBodyFatPercent(),
            'waistCm' => $this->getWaistCm(),
            'source' => $this->source->value,
            'externalId' => $this->externalId,
            'excluded' => $this->excluded,
        ], $changedProperties, self::PAYLOAD_KEYS);
    }

    /** The DECIMAL column's string, rounded to its scale. */
    private static function decimal(?float $value, int $scale): ?string
    {
        return null === $value ? null : number_format($value, $scale, '.', '');
    }
}
