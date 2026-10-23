<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\PlacementFrequency;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Service\PlacementSimulator;
use Maggie\Finance\State\CreatePlacementProcessor;
use Maggie\Finance\State\DeletePlacementProcessor;
use Maggie\Finance\State\UpdatePlacementProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// A simulated placement (MAG-555): an initial payment, a deposit paid monthly,
// quarterly, yearly or once, an annual rate and a duration. Nothing is booked:
// final, paid, interest and rent are computed by PlacementSimulator, the curve
// month by month by GET /api/finance/placements/{id}/simulation. The scenarios
// (MAG-556), the investments (MAG-557) and the independence (MAG-558) read it.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: PlacementRepository::class)]
#[Indexed(index: 'placements', module: 'finance')]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/finance/placements', order: ['name' => 'ASC'], provider: ElasticsearchCollectionProvider::class),
    new Get(uriTemplate: '/finance/placements/{id}', provider: ElasticsearchItemProvider::class),
    new Post(uriTemplate: '/finance/placements', processor: CreatePlacementProcessor::class),
    new Patch(uriTemplate: '/finance/placements/{id}', processor: UpdatePlacementProcessor::class),
    new Delete(uriTemplate: '/finance/placements/{id}', processor: DeletePlacementProcessor::class),
])]
class Placement implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    /** The collection path, and the Mercure topic the clients subscribe to. */
    public const COLLECTION = '/api/finance/placements';

    public const MAX_YEARS = 60;

    /** 100 %: anything above is a typo, not a placement. */
    public const MAX_RATE_BASIS_POINTS = 10000;

    /** 10 M€: within the INT columns, and far beyond any household placement. */
    public const MAX_CENTS = 1_000_000_000;

    public const NOTHING_PAID = 'A placement needs an initial payment or a deposit.';

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[Assert\Length(max: 255)]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $name;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: self::MAX_CENTS)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'What is placed on day one, in integer cents.')]
    private int $initial = 0;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: self::MAX_CENTS)]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'Each deposit, in integer cents: paid every period, or once on day one when frequency is once.')]
    private int $deposit = 0;

    #[ORM\Column(length: 16, enumType: PlacementFrequency::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'monthly, quarterly, yearly: the deposit at the end of each period; once: a single deposit on day one.')]
    private PlacementFrequency $frequency = PlacementFrequency::Monthly;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 0, max: self::MAX_RATE_BASIS_POINTS)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'Annual return in basis points: 600 is 6 %. Compounded monthly at (1 + r)^(1/12) − 1, interest reinvested.')]
    private int $annualRate = 0;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 1, max: self::MAX_YEARS)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'How long the placement runs, in whole years (1 to 60).')]
    private int $years = 10;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    /** @var array{final: int, paid: int, interest: int, rent: int, curve: list<array{month: int, capital: int, paid: int}>}|null not mapped */
    private ?array $simulation = null;

    private ?string $simulationKey = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validatePayments(ExecutionContextInterface $context): void
    {
        if (0 === $this->initial && 0 === $this->deposit) {
            $context->buildViolation(self::NOTHING_PAID)->atPath('deposit')->addViolation();
        }
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

    public function getInitial(): int
    {
        return $this->initial;
    }

    public function setInitial(int $initial): static
    {
        $this->initial = $initial;

        return $this;
    }

    public function getDeposit(): int
    {
        return $this->deposit;
    }

    public function setDeposit(int $deposit): static
    {
        $this->deposit = $deposit;

        return $this;
    }

    public function getFrequency(): PlacementFrequency
    {
        return $this->frequency;
    }

    public function setFrequency(PlacementFrequency $frequency): static
    {
        $this->frequency = $frequency;

        return $this;
    }

    public function getAnnualRate(): int
    {
        return $this->annualRate;
    }

    public function setAnnualRate(int $annualRate): static
    {
        $this->annualRate = $annualRate;

        return $this;
    }

    public function getYears(): int
    {
        return $this->years;
    }

    public function setYears(int $years): static
    {
        $this->years = $years;

        return $this;
    }

    #[ApiProperty(description: 'The capital at the end, in integer cents.')]
    public function getFinal(): int
    {
        return $this->simulation()['final'];
    }

    #[ApiProperty(description: 'Everything paid in over the duration, initial included, in integer cents.')]
    public function getPaid(): int
    {
        return $this->simulation()['paid'];
    }

    #[ApiProperty(description: 'The compound interest earned: final − paid, in integer cents.')]
    public function getInterest(): int
    {
        return $this->simulation()['interest'];
    }

    #[ApiProperty(description: 'A simulation: what the final capital would give a month, withdrawn at 4 % a year (final × 4 % / 12), in integer cents.')]
    public function getRent(): int
    {
        return $this->simulation()['rent'];
    }

    #[ApiProperty(description: 'What the deposits come to per month on average, in integer cents: 0 for a single one.')]
    public function getMonthlyDeposit(): int
    {
        return $this->frequency->monthlyCents($this->deposit);
    }

    /**
     * Computed once per set of figures: the four getters, the Mercure payload
     * and the MCP view all read it.
     *
     * @return array{final: int, paid: int, interest: int, rent: int, curve: list<array{month: int, capital: int, paid: int}>}
     */
    private function simulation(): array
    {
        $key = implode(':', [$this->initial, $this->deposit, $this->frequency->value, $this->annualRate, $this->years]);
        if (null === $this->simulation || $this->simulationKey !== $key) {
            $this->simulation = (new PlacementSimulator())->of($this);
            $this->simulationKey = $key;
        }

        return $this->simulation;
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
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'initial' => $this->initial,
            'deposit' => $this->deposit,
            'frequency' => $this->frequency->value,
            'annualRate' => $this->annualRate,
            'years' => $this->years,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        // The simulated figures follow every field they are computed from.
        if (null !== $changedProperties && [] !== array_intersect($changedProperties, ['initial', 'deposit', 'frequency', 'annualRate', 'years'])) {
            array_push($changedProperties, 'final', 'paid', 'interest', 'rent', 'monthlyDeposit');
        }

        $simulation = $this->simulation();

        return self::filterPayload([
            'name' => $this->name,
            'initial' => $this->initial,
            'deposit' => $this->deposit,
            'frequency' => $this->frequency->value,
            'annualRate' => $this->annualRate,
            'years' => $this->years,
            'final' => $simulation['final'],
            'paid' => $simulation['paid'],
            'interest' => $simulation['interest'],
            'rent' => $simulation['rent'],
            'monthlyDeposit' => $this->getMonthlyDeposit(),
        ], $changedProperties);
    }
}
