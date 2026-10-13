<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

/**
 * The treasury settings, one row per user, created on first read like the
 * safety cushion — not piled onto it: the cushion is the safety net, these
 * are settings (shape decision 16).
 *
 * Read and written through `/api/finance/treasury-settings`, never through an
 * API Platform operation on an identifier-less `/me` path, which would build a
 * fresh entity on PATCH.
 */
#[ORM\Entity(repositoryClass: TreasurySettingsRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_treasury_settings_user', columns: ['user_id'])]
class TreasurySettings implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /**
     * Day of the month the salary is expected on. Null until the owner says
     * it: a default of the 25th would claim to know, and call a salary late
     * for someone who never declared one.
     */
    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\Range(min: 1, max: 31)]
    private ?int $salaryExpectedDay = null;

    /**
     * The overdraft the bank allows, positive cents. 0 means any negative
     * balance is an overdraft — the safe default for someone who never said.
     */
    #[ORM\Column(type: 'integer', options: ['default' => 0])]
    #[Assert\PositiveOrZero]
    private int $overdraftLimitCents = 0;

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

    public function getSalaryExpectedDay(): ?int
    {
        return $this->salaryExpectedDay;
    }

    public function setSalaryExpectedDay(?int $salaryExpectedDay): static
    {
        $this->salaryExpectedDay = $salaryExpectedDay;

        return $this;
    }

    public function getOverdraftLimitCents(): int
    {
        return $this->overdraftLimitCents;
    }

    public function setOverdraftLimitCents(int $overdraftLimitCents): static
    {
        $this->overdraftLimitCents = $overdraftLimitCents;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'salaryExpectedDay' => $this->salaryExpectedDay,
            'overdraftLimitCents' => $this->overdraftLimitCents,
        ], $changedProperties);
    }
}
