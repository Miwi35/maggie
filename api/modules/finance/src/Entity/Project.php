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
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
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
use Maggie\Core\Mercure\MercureTopic;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Serializer\StrictDayNormalizer;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Enum\ProjectDateMode;
use Maggie\Finance\Enum\ProjectWellbeing;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\State\CreateProjectProcessor;
use Maggie\Finance\State\DeleteProjectProcessor;
use Maggie\Finance\State\UpdateProjectProcessor;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\AbstractObjectNormalizer;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// A project the user saves for (MAG-519): an amount, how much it counts for
// their wellbeing, an optional date and the projects it waits for. `position`
// is the user's priority order, 0 first: set on create (at the end), changed
// only by POST /api/finance/projects/reorder, compacted on delete — unique per
// user, so every renumbering goes through ProjectPositions.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: ProjectRepository::class)]
#[ORM\UniqueConstraint(name: 'project_user_position', columns: ['user_id', 'position'])]
#[Indexed(index: 'projects', module: 'finance')]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/finance/projects', order: ['position' => 'ASC'], provider: ElasticsearchCollectionProvider::class),
    new Get(uriTemplate: '/finance/projects/{id}', provider: ElasticsearchItemProvider::class),
    new Post(uriTemplate: '/finance/projects', processor: CreateProjectProcessor::class),
    new Patch(uriTemplate: '/finance/projects/{id}', processor: UpdateProjectProcessor::class),
    new Delete(uriTemplate: '/finance/projects/{id}', processor: DeleteProjectProcessor::class),
])]
class Project implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    /** The collection path, and the Mercure topic the clients subscribe to. */
    public const COLLECTION = '/api/finance/projects';

    public const SELF_DEPENDENCY = 'A project cannot depend on itself.';

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
    #[Assert\Positive]
    #[IndexedField(type: 'long')]
    #[ApiProperty(description: 'What the project costs, in integer cents.')]
    private int $amountCents = 0;

    #[ORM\Column(length: 3)]
    #[Assert\NotBlank]
    #[Assert\Length(exactly: 3)]
    #[Assert\Regex(pattern: '/^[A-Z]{3}$/', message: 'The currency must be a 3-letter ISO 4217 code.')]
    #[IndexedField(type: 'keyword')]
    private string $currency = 'EUR';

    #[ORM\Column(length: 16, enumType: ProjectWellbeing::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'How much the project counts for the user\'s wellbeing: essential (4), high (3), medium (2), comfort (1).')]
    private ProjectWellbeing $wellbeing = ProjectWellbeing::Medium;

    #[ORM\Column(type: 'integer')]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(writable: false, description: 'Priority order, 0 first. Read-only: changed by POST /api/finance/projects/reorder.')]
    private int $position = 0;

    #[ORM\Column(length: 16, enumType: ProjectDateMode::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(description: 'none: no date, the planning places it; fixed: a locked date; deadline: at the latest on targetOn.')]
    private ProjectDateMode $dateMode = ProjectDateMode::None;

    // A day, `Y-m-d` both ways (see Meal::$date). Required by fixed and
    // deadline, refused by none.
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(
        description: 'The project\'s date, YYYY-MM-DD: required when dateMode is fixed or deadline, null when none.',
        openapiContext: ['type' => 'string', 'format' => 'date', 'nullable' => true, 'example' => '2027-03-01'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d', AbstractObjectNormalizer::SKIP_NULL_VALUES => false],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $targetOn = null;

    /** @var Collection<int, Project> projects of the same user this one waits for */
    #[ORM\ManyToMany(targetEntity: self::class)]
    #[ORM\JoinTable(name: 'project_dependency')]
    #[ORM\JoinColumn(name: 'project_id', onDelete: 'CASCADE')]
    #[ORM\InverseJoinColumn(name: 'depends_on_id', onDelete: 'CASCADE')]
    #[ApiProperty(description: 'The projects (IRIs) this one waits for, all the user\'s own.')]
    private Collection $dependsOn;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    #[Context(normalizationContext: [AbstractObjectNormalizer::SKIP_NULL_VALUES => false])]
    private ?string $note = null;

    /**
     * Where the money comes from besides the salary (MAG-524). Read-only:
     * written by PUT /api/finance/projects/{id}/funding, the whole list.
     *
     * @var Collection<int, ProjectFundingSource>
     */
    #[ORM\OneToMany(targetEntity: ProjectFundingSource::class, mappedBy: 'project', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ORM\OrderBy(['position' => 'ASC'])]
    // Plain objects in the project, without a generated `@id`.
    #[ApiProperty(writable: false, genId: false, description: 'Where the money comes from besides the salary: [{kind (savings, capital, loan), amountCents, accountId, loanId}]. Read-only: written by PUT /api/finance/projects/{id}/funding.')]
    // accountId and loanId are null for the kinds without them: always served.
    #[Context(normalizationContext: [AbstractObjectNormalizer::SKIP_NULL_VALUES => false])]
    private Collection $fundingSources;

    /**
     * What makes the project an investment (MAG-557), null for any other.
     * Read-only: written by PUT /api/finance/projects/{id}/investment.
     */
    #[ORM\OneToOne(targetEntity: ProjectInvestment::class, mappedBy: 'project', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ApiProperty(writable: false, genId: false, description: 'Set when the project is an investment: {projectId, priceCents, downPaymentCents, loanId, loanPaymentCents, monthlyRentCents, monthlyBalanceCents, resale: {month, netAmountCents, targetProjectId} | null}. Null for any other project. Read-only: written by PUT /api/finance/projects/{id}/investment.')]
    #[Context(normalizationContext: [AbstractObjectNormalizer::SKIP_NULL_VALUES => false])]
    private ?ProjectInvestment $investment = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->dependsOn = new ArrayCollection();
        $this->fundingSources = new ArrayCollection();
    }

    #[Assert\Callback]
    public function validateDateAndDependencies(ExecutionContextInterface $context): void
    {
        if (null !== $message = self::dateProblem($this->dateMode, $this->targetOn)) {
            $context->buildViolation($message)->atPath('targetOn')->addViolation();
        }

        foreach ($this->dependsOn as $dependency) {
            if ((string) $dependency->getId() === (string) $this->id) {
                $context->buildViolation(self::SELF_DEPENDENCY)->atPath('dependsOn')->addViolation();
            }
        }
    }

    /** Why a date mode and a date do not go together, or null when they do. */
    public static function dateProblem(ProjectDateMode $mode, ?\DateTimeImmutable $targetOn): ?string
    {
        if ($mode->needsDate() && null === $targetOn) {
            return \sprintf('targetOn is required when dateMode is %s.', $mode->value);
        }

        if (!$mode->needsDate() && null !== $targetOn) {
            return 'targetOn must be empty when dateMode is none: clear it, or choose fixed or deadline.';
        }

        return null;
    }

    /**
     * A day from the `Y-m-d` the MCP tool and the commands carry.
     *
     * @throws \DomainException when the string is not a day
     */
    public static function dayFromString(string $day): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day, new \DateTimeZone('UTC'));

        if (false === $parsed || $parsed->format('Y-m-d') !== $day) {
            throw new \DomainException("Not a day: \"{$day}\". targetOn is YYYY-MM-DD.");
        }

        return $parsed;
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

    public function getAmountCents(): int
    {
        return $this->amountCents;
    }

    public function setAmountCents(int $amountCents): static
    {
        $this->amountCents = $amountCents;

        return $this;
    }

    public function getCurrency(): string
    {
        return $this->currency;
    }

    public function setCurrency(string $currency): static
    {
        $this->currency = $currency;

        return $this;
    }

    public function getWellbeing(): ProjectWellbeing
    {
        return $this->wellbeing;
    }

    public function setWellbeing(ProjectWellbeing $wellbeing): static
    {
        $this->wellbeing = $wellbeing;

        return $this;
    }

    /** 4 for essential down to 1 for comfort. */
    public function getWellbeingScore(): int
    {
        return $this->wellbeing->score();
    }

    public function getPosition(): int
    {
        return $this->position;
    }

    public function setPosition(int $position): static
    {
        $this->position = $position;

        return $this;
    }

    public function getDateMode(): ProjectDateMode
    {
        return $this->dateMode;
    }

    public function setDateMode(ProjectDateMode $dateMode): static
    {
        $this->dateMode = $dateMode;

        return $this;
    }

    public function getTargetOn(): ?\DateTimeImmutable
    {
        return $this->targetOn;
    }

    /**
     * Only the day is kept: a `DATE` column holds a day. The same day keeps
     * the same instance — Doctrine would take a new one for a change.
     */
    public function setTargetOn(?\DateTimeImmutable $targetOn): static
    {
        if ($targetOn?->format('Y-m-d') === $this->targetOn?->format('Y-m-d')) {
            return $this;
        }

        $this->targetOn = null === $targetOn ? null : new \DateTimeImmutable($targetOn->format('Y-m-d'), new \DateTimeZone('UTC'));

        return $this;
    }

    /** @return Collection<int, Project> */
    public function getDependsOn(): Collection
    {
        return $this->dependsOn;
    }

    /**
     * The whole list: replaces the current one with the very instances given.
     * A PATCH resolves the IRIs through the Elasticsearch item provider, whose
     * unmanaged copies land here first: the managed ones the handler passes
     * must replace them, or Doctrine takes them for new entities on flush.
     *
     * @param iterable<Project> $dependsOn
     */
    public function setDependsOn(iterable $dependsOn): static
    {
        $wanted = [];
        foreach ($dependsOn as $project) {
            $wanted[(string) $project->getId()] = $project;
        }

        foreach ($this->dependsOn->toArray() as $current) {
            $id = (string) $current->getId();
            if (!isset($wanted[$id]) || $wanted[$id] !== $current) {
                $this->dependsOn->removeElement($current);
            }
        }

        foreach ($wanted as $project) {
            $this->addDependency($project);
        }
        $this->keepDependenciesAList();

        return $this;
    }

    public function addDependency(Project $project): static
    {
        foreach ($this->dependsOn as $current) {
            if ((string) $current->getId() === (string) $project->getId()) {
                return $this;
            }
        }

        $this->dependsOn->add($project);

        return $this;
    }

    public function removeDependency(Project $project): static
    {
        foreach ($this->dependsOn->toArray() as $current) {
            if ((string) $current->getId() === (string) $project->getId()) {
                $this->dependsOn->removeElement($current);
            }
        }
        $this->keepDependenciesAList();

        return $this;
    }

    /**
     * A removal leaves a gap in the keys, and a collection with gaps is
     * serialised as a JSON object the clients cannot read: renumbered from 0.
     * Doctrine rewrites the join rows of a cleared collection.
     */
    private function keepDependenciesAList(): void
    {
        $dependencies = $this->dependsOn->toArray();
        if (array_is_list($dependencies)) {
            return;
        }

        $this->dependsOn->clear();
        foreach ($dependencies as $project) {
            $this->dependsOn->add($project);
        }
    }

    /** @return list<string> the ids of the projects this one waits for */
    public function dependencyIds(): array
    {
        return array_values(array_map(static fn (Project $p): string => (string) $p->getId(), $this->dependsOn->toArray()));
    }

    /**
     * The projects this one waits for whose date it does not respect: it is
     * dated before them. Only dated projects on both sides can disagree.
     *
     * @return list<string> their ids
     */
    public function getUnmetDependencies(): array
    {
        if (null === $this->targetOn) {
            return [];
        }

        $mine = $this->targetOn->format('Y-m-d');
        $unmet = [];
        foreach ($this->dependsOn as $dependency) {
            $theirs = $dependency->getTargetOn()?->format('Y-m-d');
            if (null !== $theirs && $mine < $theirs) {
                $unmet[] = (string) $dependency->getId();
            }
        }

        return $unmet;
    }

    public function getNote(): ?string
    {
        return $this->note;
    }

    public function setNote(?string $note): static
    {
        $this->note = $note;

        return $this;
    }

    /** @return list<ProjectFundingSource> */
    public function getFundingSources(): array
    {
        return array_values($this->fundingSources->toArray());
    }

    /** For `ProjectFundingSource::setProject` only. */
    public function attachFundingSource(ProjectFundingSource $source): void
    {
        if (!$this->fundingSources->contains($source)) {
            $this->fundingSources->add($source);
        }
    }

    public function removeFundingSource(ProjectFundingSource $source): void
    {
        $this->fundingSources->removeElement($source);
    }

    public function getInvestment(): ?ProjectInvestment
    {
        return $this->investment;
    }

    public function setInvestment(?ProjectInvestment $investment): static
    {
        $this->investment = $investment;

        return $this;
    }

    public function loanSource(): ?ProjectFundingSource
    {
        foreach ($this->fundingSources as $source) {
            if (FundingSourceKind::Loan === $source->getKind()) {
                return $source;
            }
        }

        return null;
    }

    /** What the sources cover. */
    public function fundedCents(): int
    {
        return array_sum(array_map(static fn (ProjectFundingSource $source): int => $source->getAmountCents(), $this->fundingSources->toArray()));
    }

    /**
     * What is left to save on the salary: the amount minus the sources, never
     * below 0 — a project made cheaper than its sources needs nothing more.
     */
    #[ApiProperty(description: 'What is left to save on the salary: amountCents minus the funding sources, never below 0.')]
    public function getSalaryCents(): int
    {
        return max(0, $this->amountCents - $this->fundedCents());
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
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'wellbeing' => $this->wellbeing->value,
            'position' => $this->position,
            'dateMode' => $this->dateMode->value,
            'targetOn' => $this->targetOn?->format('Y-m-d'),
            'note' => $this->note,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        // A computed field follows what it is computed from.
        if (null !== $changedProperties) {
            if (\in_array('wellbeing', $changedProperties, true)) {
                $changedProperties[] = 'wellbeingScore';
            }
            if (\in_array('targetOn', $changedProperties, true) || \in_array('dependsOn', $changedProperties, true)) {
                $changedProperties[] = 'unmetDependencies';
            }
            if (\in_array('amountCents', $changedProperties, true) || \in_array('fundingSources', $changedProperties, true)) {
                $changedProperties[] = 'salaryCents';
                $changedProperties[] = 'investment';
            }
        }

        return self::filterPayload([
            'name' => $this->name,
            'amountCents' => $this->amountCents,
            'currency' => $this->currency,
            'wellbeing' => $this->wellbeing->value,
            'wellbeingScore' => $this->getWellbeingScore(),
            'position' => $this->position,
            'dateMode' => $this->dateMode->value,
            'targetOn' => $this->targetOn?->format('Y-m-d'),
            // IRIs, as the REST read serves them.
            'dependsOn' => array_map(static fn (string $id): string => MercureTopic::item(self::COLLECTION, $id), $this->dependencyIds()),
            'note' => $this->note,
            'unmetDependencies' => $this->getUnmetDependencies(),
            'fundingSources' => array_map(static fn (ProjectFundingSource $source): array => $source->toArray(), $this->getFundingSources()),
            'salaryCents' => $this->getSalaryCents(),
            'investment' => $this->investment?->toArray(),
        ], $changedProperties);
    }
}
