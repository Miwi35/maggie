<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;
use Maggie\Finance\Repository\ScenarioRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A way to run the user's projects (MAG-550): a selection and an order of
 * projects that already exist (never changed by the scenario), early
 * repayments simulated, placements and a monthly saving.
 *
 * At most one scenario per user is `current`: the one the Dashboard, the
 * treasury, the home and the date N follow. Nothing is current until the user
 * says so. The three automatic scenarios are not stored until they are made
 * current or duplicated; then they become an ordinary one, order frozen.
 *
 * What it costs and saves (metrics, date N) is computed on read and never
 * stored. Not an API resource: read and written through the scenario endpoints
 * and announced on Mercure.
 */
#[ORM\Entity(repositoryClass: ScenarioRepository::class)]
#[ORM\Table(name: 'scenario')]
#[ORM\UniqueConstraint(name: 'uniq_scenario_current_per_user', columns: ['user_id'], options: ['where' => '(is_current = true)'])]
#[MercureCollection(self::COLLECTION)]
class Scenario implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    /** The collection path, and the Mercure topic the clients subscribe to. */
    public const COLLECTION = '/api/finance/scenarios';

    public const MAX_NAME_LENGTH = 120;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    #[ORM\Column(length: self::MAX_NAME_LENGTH)]
    private string $name;

    #[ORM\Column(length: 16, enumType: ScenarioKind::class)]
    private ScenarioKind $kind = ScenarioKind::User;

    #[ORM\Column(length: 16, enumType: ScenarioStatus::class)]
    private ScenarioStatus $status = ScenarioStatus::Saved;

    #[ORM\Column(name: 'is_current', type: 'boolean', options: ['default' => false])]
    private bool $current = false;

    /** Which automatic scenario this one was made from: `rapid`, `balanced` or `quality`. */
    #[ORM\Column(length: 16, nullable: true)]
    private ?string $autoKey = null;

    /** @var list<array{projectId: string, position: int}> */
    #[ORM\Column(type: 'json')]
    private array $projects = [];

    /** @var list<array<string, mixed>> */
    #[ORM\Column(type: 'json')]
    private array $repayments = [];

    /** @var list<array{placementId: string}> */
    #[ORM\Column(type: 'json')]
    private array $placements = [];

    /** Replaces the net saving capacity when set, in integer cents. */
    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $monthlySaving = null;

    public function __construct()
    {
        $this->id = new Ulid();
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

    public function getKind(): ScenarioKind
    {
        return $this->kind;
    }

    public function setKind(ScenarioKind $kind): static
    {
        $this->kind = $kind;

        return $this;
    }

    public function getStatus(): ScenarioStatus
    {
        return $this->status;
    }

    public function setStatus(ScenarioStatus $status): static
    {
        $this->status = $status;

        return $this;
    }

    public function isCurrent(): bool
    {
        return $this->current;
    }

    public function setCurrent(bool $current): static
    {
        $this->current = $current;

        return $this;
    }

    public function getAutoKey(): ?string
    {
        return $this->autoKey;
    }

    public function setAutoKey(?string $autoKey): static
    {
        $this->autoKey = $autoKey;

        return $this;
    }

    /** @return list<array{projectId: string, position: int}> */
    public function getProjects(): array
    {
        return $this->projects;
    }

    /** @param list<array{projectId: string, position: int}> $projects */
    public function setProjects(array $projects): static
    {
        $this->projects = $projects;

        return $this;
    }

    /** @return list<array<string, mixed>> */
    public function getRepayments(): array
    {
        return $this->repayments;
    }

    /** @param list<array<string, mixed>> $repayments */
    public function setRepayments(array $repayments): static
    {
        $this->repayments = $repayments;

        return $this;
    }

    /** @return list<array{placementId: string}> */
    public function getPlacements(): array
    {
        return $this->placements;
    }

    /** @param list<array{placementId: string}> $placements */
    public function setPlacements(array $placements): static
    {
        $this->placements = $placements;

        return $this;
    }

    public function getMonthlySaving(): ?int
    {
        return $this->monthlySaving;
    }

    public function setMonthlySaving(?int $monthlySaving): static
    {
        $this->monthlySaving = $monthlySaving;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'kind' => $this->kind->value,
            'status' => $this->status->value,
            'current' => $this->current,
            'projects' => $this->projects,
            'repayments' => $this->repayments,
            'placements' => $this->placements,
            'monthlySaving' => $this->monthlySaving,
        ], $changedProperties);
    }
}
