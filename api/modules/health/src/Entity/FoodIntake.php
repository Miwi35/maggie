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
use Maggie\Health\Enum\FoodIntakeSource;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\State\CreateFoodIntakeProcessor;
use Maggie\Health\State\DeleteFoodIntakeProcessor;
use Maggie\Health\State\UpdateFoodIntakeProcessor;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// What the user ate (MAG-748): « Mangé » on a planned meal, a food typed by
// hand or told to Maggie. The figures are copied when it is recorded — a
// recipe changed afterwards, or a meal deleted, never rewrites the past — so
// `mealId` is a plain identifier, not a relation to the cookbook's meal. One
// intake per meal and user (`uniq_food_intake_user_meal`).
//
// `date` is the local day the intake counts for: the meal's day, or the day of
// `eatenAt` in Paris. Served by Doctrine, like Workout: nothing is searched.
//
// Every field is optional on the way in: a POST may carry `mealId` alone, and
// what is missing is refused by the handler ({@see \Maggie\Health\Service\FoodIntakeRules}),
// the same for the API and the MCP tool.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: FoodIntakeRepository::class)]
#[ORM\Index(name: 'idx_food_intake_user_date', columns: ['user_id', 'date'])]
#[ORM\UniqueConstraint(name: 'uniq_food_intake_user_meal', columns: ['user_id', 'meal_id'])]
#[ApiFilter(DateFilter::class, properties: ['date'])]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/health/food-intakes', order: ['eatenAt' => 'ASC'], paginationMaximumItemsPerPage: 1000),
    new Get(uriTemplate: '/health/food-intakes/{id}'),
    new Post(uriTemplate: '/health/food-intakes', processor: CreateFoodIntakeProcessor::class),
    new Patch(uriTemplate: '/health/food-intakes/{id}', processor: UpdateFoodIntakeProcessor::class),
    new Delete(uriTemplate: '/health/food-intakes/{id}', processor: DeleteFoodIntakeProcessor::class),
])]
class FoodIntake implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const COLLECTION = '/api/health/food-intakes';

    /** {@see Workout::NO_OFFSET_ZONE}: a time without offset is refused, never read in the server's zone. */
    private const NO_OFFSET_ZONE = 'Pacific/Kiritimati';

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(type: Types::DATE_IMMUTABLE)]
    #[Context(normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'])]
    #[ApiProperty(
        writable: false,
        description: 'The local day the intake counts for, YYYY-MM-DD: the meal\'s day, or the day of eatenAt in Paris.',
        openapiContext: ['type' => 'string', 'format' => 'date', 'example' => '2026-11-07'],
    )]
    private ?\DateTimeImmutable $date = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[Context(denormalizationContext: [DateTimeNormalizer::TIMEZONE_KEY => self::NO_OFFSET_ZONE])]
    #[ApiProperty(description: 'When it was eaten: ISO 8601 with offset, e.g. 2026-11-07T12:30:00+01:00. Required without mealId; with mealId it defaults to now.')]
    private ?\DateTimeImmutable $eatenAt = null;

    #[ORM\Column(length: 26, nullable: true)]
    #[ApiProperty(description: 'The planned meal eaten (« Mangé »): its figures are copied, one serving of each recipe. Once per meal. Set on creation only.')]
    private ?string $mealId = null;

    #[ORM\Column(length: 16, nullable: true)]
    #[ApiProperty(description: 'The Ciqual food eaten: with quantityGrams, the figures are computed from Ciqual unless given.')]
    private ?string $ciqualCode = null;

    #[ORM\Column(length: 255)]
    #[ApiProperty(description: 'What was eaten, e.g. « Salade César ». A meal\'s title, or the Ciqual food\'s name, when not given.')]
    private ?string $label = null;

    #[ORM\Column(type: Types::FLOAT, nullable: true)]
    #[ApiProperty(description: 'Grams eaten, e.g. 150.')]
    private ?float $quantityGrams = null;

    #[ORM\Column(type: Types::INTEGER)]
    #[ApiProperty(description: 'Kilocalories, an integer from 0 to 10000.')]
    private ?int $kcal = null;

    #[ORM\Column(type: Types::FLOAT)]
    #[ApiProperty(description: 'Proteins in grams; 0 when not given.')]
    private ?float $proteinG = null;

    #[ORM\Column(type: Types::FLOAT)]
    #[ApiProperty(description: 'Carbohydrates in grams; 0 when not given.')]
    private ?float $carbsG = null;

    #[ORM\Column(type: Types::FLOAT)]
    #[ApiProperty(description: 'Fat in grams; 0 when not given.')]
    private ?float $fatG = null;

    #[ORM\Column(length: 16, enumType: FoodIntakeSource::class)]
    #[ApiProperty(writable: false, description: 'meal (« Mangé »), manual (typed) or conversation (told to Maggie).')]
    private FoodIntakeSource $source = FoodIntakeSource::Manual;

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
        if (null !== $this->eatenAt && self::NO_OFFSET_ZONE === $this->eatenAt->getTimezone()->getName()) {
            $context->buildViolation('eatenAt must have a time and an offset, e.g. 2026-11-07T12:30:00+01:00.')->atPath('eatenAt')->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getDate(): ?\DateTimeImmutable
    {
        return $this->date;
    }

    public function setDate(\DateTimeImmutable $date): static
    {
        $this->date = $date;

        return $this;
    }

    public function getEatenAt(): ?\DateTimeImmutable
    {
        return $this->eatenAt;
    }

    public function setEatenAt(?\DateTimeImmutable $eatenAt): static
    {
        $this->eatenAt = $eatenAt;

        return $this;
    }

    public function getMealId(): ?string
    {
        return $this->mealId;
    }

    public function setMealId(?string $mealId): static
    {
        $this->mealId = $mealId;

        return $this;
    }

    public function getCiqualCode(): ?string
    {
        return $this->ciqualCode;
    }

    public function setCiqualCode(?string $ciqualCode): static
    {
        $this->ciqualCode = $ciqualCode;

        return $this;
    }

    public function getLabel(): ?string
    {
        return $this->label;
    }

    public function setLabel(?string $label): static
    {
        $this->label = $label;

        return $this;
    }

    public function getQuantityGrams(): ?float
    {
        return $this->quantityGrams;
    }

    public function setQuantityGrams(?float $quantityGrams): static
    {
        $this->quantityGrams = null === $quantityGrams ? null : round($quantityGrams, 1);

        return $this;
    }

    public function getKcal(): ?int
    {
        return $this->kcal;
    }

    public function setKcal(?int $kcal): static
    {
        $this->kcal = $kcal;

        return $this;
    }

    public function getProteinG(): ?float
    {
        return $this->proteinG;
    }

    public function setProteinG(?float $proteinG): static
    {
        $this->proteinG = null === $proteinG ? null : round($proteinG, 1);

        return $this;
    }

    public function getCarbsG(): ?float
    {
        return $this->carbsG;
    }

    public function setCarbsG(?float $carbsG): static
    {
        $this->carbsG = null === $carbsG ? null : round($carbsG, 1);

        return $this;
    }

    public function getFatG(): ?float
    {
        return $this->fatG;
    }

    public function setFatG(?float $fatG): static
    {
        $this->fatG = null === $fatG ? null : round($fatG, 1);

        return $this;
    }

    public function getSource(): FoodIntakeSource
    {
        return $this->source;
    }

    public function setSource(FoodIntakeSource $source): static
    {
        $this->source = $source;

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
            'date' => $this->date?->format('Y-m-d'),
            'eatenAt' => $this->eatenAt?->format(\DATE_ATOM),
            'mealId' => $this->mealId,
            'ciqualCode' => $this->ciqualCode,
            'label' => $this->label,
            'quantityGrams' => $this->quantityGrams,
            'kcal' => $this->kcal,
            'proteinG' => $this->proteinG,
            'carbsG' => $this->carbsG,
            'fatG' => $this->fatG,
            'source' => $this->source->value,
        ], $changedProperties);
    }
}
