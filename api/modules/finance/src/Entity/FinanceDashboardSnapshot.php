<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Symfony\Component\Uid\Ulid;

/**
 * The read model of the Finance Dashboard (MAG-545): the answer of
 * `GET /api/finance/dashboard` for the month it was computed, one row per user.
 *
 * Only the refresh writes `payload`. Every other change to the user's finance
 * rows marks the row stale through SQL ({@see FinanceDashboardSnapshotRepository::markStale()}),
 * never through this entity, so a read can tell « up to date » from « a write
 * landed since » and a refresh that raced a write cannot declare itself fresh.
 *
 * Not an API resource: it is read through the dashboard endpoint and announced
 * on Mercure.
 */
#[ORM\Entity(repositoryClass: FinanceDashboardSnapshotRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_finance_dashboard_snapshot_user', columns: ['user_id'])]
class FinanceDashboardSnapshot implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    /** The month the payload was computed for. */
    #[ORM\Column(type: 'smallint', options: ['default' => 0])]
    private int $year = 0;

    #[ORM\Column(type: 'smallint', options: ['default' => 0])]
    private int $month = 0;

    /** The user's day the payload was computed on: the rings move with the date. */
    #[ORM\Column(type: 'date_immutable', nullable: true)]
    private ?\DateTimeImmutable $computedOn = null;

    /** The response body, as sent: JSON text, byte for byte what the live computation answers. */
    #[ORM\Column(type: 'text', nullable: true)]
    private ?string $payload = null;

    #[ORM\Column(type: 'boolean', options: ['default' => true])]
    private bool $stale = true;

    /** Bumped by every write that matters; read-only here, see the class comment. */
    #[ORM\Column(type: 'integer', options: ['default' => 1])]
    private int $version = 1;

    public function __construct(User $user)
    {
        $this->id = new Ulid();
        $this->user = $user;
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

    public function getYear(): int
    {
        return $this->year;
    }

    public function getMonth(): int
    {
        return $this->month;
    }

    public function getComputedOn(): ?\DateTimeImmutable
    {
        return $this->computedOn;
    }

    public function getPayload(): ?string
    {
        return $this->payload;
    }

    public function isStale(): bool
    {
        return $this->stale;
    }

    public function getVersion(): int
    {
        return $this->version;
    }

    public function store(int $year, int $month, \DateTimeImmutable $computedOn, string $payload, bool $stale): void
    {
        $this->year = $year;
        $this->month = $month;
        $this->computedOn = $computedOn;
        $this->payload = $payload;
        $this->stale = $stale;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'year' => $this->year,
            'month' => $this->month,
            'computedOn' => $this->computedOn?->format('Y-m-d'),
            'stale' => $this->stale,
            'dashboard' => null === $this->payload ? null : json_decode($this->payload, true, 512, \JSON_THROW_ON_ERROR),
        ], $changedProperties, ['payload' => 'dashboard']);
    }
}
