<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Maggie\Finance\State\SafetyCushionItemProvider;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

/**
 * The safety net, configured once per user. Its current amount is never
 * stored here — it is the balance of the accounts flagged as cushion.
 */
#[ORM\Entity(repositoryClass: SafetyCushionRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_safety_cushion_user', columns: ['user_id'])]
// Read-only here: writes go through PATCH /api/finance/cushion-config, because
// API Platform does not populate the provider's object on an identifier-less
// PATCH — it builds a fresh one, whose id matches no row.
#[ApiResource(operations: [
    new Get(uriTemplate: '/safety_cushions/me', provider: SafetyCushionItemProvider::class),
])]
class SafetyCushion implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /** How many months of net income the cushion should hold. */
    #[ORM\Column(type: 'integer', options: ['default' => 3])]
    #[Assert\Range(min: 1, max: 24)]
    private int $targetMonths = 3;

    /** Reference monthly net income, in cents. */
    #[ORM\Column(type: 'integer', options: ['default' => 0])]
    #[Assert\PositiveOrZero]
    private int $monthlyNetIncomeCents = 0;

    /** Most that may be put back into the cushion per month, in cents. */
    #[ORM\Column(type: 'integer', options: ['default' => 15000])]
    #[Assert\PositiveOrZero]
    private int $rechargeCapCents = 15000;

    /** Months the user would like a recharge to take, before the cap applies. */
    #[ORM\Column(type: 'integer', options: ['default' => 6])]
    #[Assert\Range(min: 1, max: 60)]
    private int $rechargeTargetMonths = 6;

    /** First time the target was ever reached — tells building from recharging. */
    #[ORM\Column(type: 'datetime_immutable', nullable: true)]
    private ?\DateTimeImmutable $completedAt = null;

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

    public function getTargetMonths(): int
    {
        return $this->targetMonths;
    }

    public function setTargetMonths(int $targetMonths): static
    {
        $this->targetMonths = $targetMonths;

        return $this;
    }

    public function getMonthlyNetIncomeCents(): int
    {
        return $this->monthlyNetIncomeCents;
    }

    public function setMonthlyNetIncomeCents(int $monthlyNetIncomeCents): static
    {
        $this->monthlyNetIncomeCents = $monthlyNetIncomeCents;

        return $this;
    }

    public function getRechargeCapCents(): int
    {
        return $this->rechargeCapCents;
    }

    public function setRechargeCapCents(int $rechargeCapCents): static
    {
        $this->rechargeCapCents = $rechargeCapCents;

        return $this;
    }

    public function getRechargeTargetMonths(): int
    {
        return $this->rechargeTargetMonths;
    }

    public function setRechargeTargetMonths(int $rechargeTargetMonths): static
    {
        $this->rechargeTargetMonths = $rechargeTargetMonths;

        return $this;
    }

    public function getCompletedAt(): ?\DateTimeImmutable
    {
        return $this->completedAt;
    }

    public function setCompletedAt(?\DateTimeImmutable $completedAt): static
    {
        $this->completedAt = $completedAt;

        return $this;
    }

    /** The amount the cushion should hold, in cents. */
    public function getTargetCents(): int
    {
        return $this->targetMonths * $this->monthlyNetIncomeCents;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'targetMonths' => $this->targetMonths,
            'monthlyNetIncomeCents' => $this->monthlyNetIncomeCents,
            'targetCents' => $this->getTargetCents(),
            'rechargeCapCents' => $this->rechargeCapCents,
            'rechargeTargetMonths' => $this->rechargeTargetMonths,
            'completedAt' => $this->completedAt?->format(\DateTimeInterface::ATOM),
        ], $changedProperties);
    }
}
