<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

/**
 * What makes a project an investment (MAG-557): the rent it brings and its
 * planned resale. The price is the project's amount, the loan is the project's
 * loan source and the down payment is what that loan leaves to pay. Only ever
 * shown inside its project, published and indexed as a change to it.
 */
#[ORM\Entity]
#[AggregateRoot('project')]
class ProjectInvestment
{
    /** 10 million euros, as a placement: what an INT column holds with room to add. */
    public const MAX_CENTS = 1_000_000_000;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[Ignore]
    private Ulid $id;

    #[ORM\OneToOne(targetEntity: Project::class, inversedBy: 'investment')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Ignore]
    private Project $project;

    #[ORM\Column(type: 'integer', options: ['default' => 0])]
    private int $monthlyRentCents = 0;

    /** The first day of the month the resale is planned for. */
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[Ignore]
    private ?\DateTimeImmutable $resaleOn = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[Ignore]
    private ?int $resaleNetCents = null;

    /** The project the net proceeds go to; null: they accelerate the next ones. */
    #[ORM\ManyToOne(targetEntity: Project::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[Ignore]
    private ?Project $resaleTarget = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getProject(): Project
    {
        return $this->project;
    }

    public function setProject(Project $project): static
    {
        $this->project = $project;

        return $this;
    }

    public function getMonthlyRentCents(): int
    {
        return $this->monthlyRentCents;
    }

    public function setMonthlyRentCents(int $monthlyRentCents): static
    {
        $this->monthlyRentCents = $monthlyRentCents;

        return $this;
    }

    #[Ignore]
    public function getResaleOn(): ?\DateTimeImmutable
    {
        return $this->resaleOn;
    }

    #[Ignore]
    public function getResaleNetCents(): ?int
    {
        return $this->resaleNetCents;
    }

    #[Ignore]
    public function getResaleTarget(): ?Project
    {
        return $this->resaleTarget;
    }

    public function getProjectId(): string
    {
        return (string) $this->project->getId();
    }

    /** The project's amount: what the investment costs. */
    public function getPriceCents(): int
    {
        return $this->project->getAmountCents();
    }

    /** `$month` is `Y-m`; a null `$month` clears the whole resale. */
    public function planResale(?string $month, ?int $netCents, ?Project $target): static
    {
        if (null === $month || null === $netCents) {
            $this->resaleOn = null;
            $this->resaleNetCents = null;
            $this->resaleTarget = null;

            return $this;
        }

        $first = new \DateTimeImmutable($month.'-01', new \DateTimeZone('UTC'));
        if ($first->format('Y-m-d') !== $this->resaleOn?->format('Y-m-d')) {
            $this->resaleOn = $first;
        }
        $this->resaleNetCents = $netCents;
        $this->resaleTarget = $target;

        return $this;
    }

    /** What the loan leaves to pay out of one's own pocket: the price less the amount borrowed. */
    public function getDownPaymentCents(): int
    {
        $loan = $this->project->loanSource();

        return max(0, $this->getPriceCents() - (null === $loan ? 0 : $loan->getAmountCents()));
    }

    public function getLoanId(): ?string
    {
        return $this->project->loanSource()?->getLoanId();
    }

    /** The monthly payment the loan costs, insurance included; 0 without a loan. */
    public function getLoanPaymentCents(): int
    {
        $loan = $this->project->loanSource()?->getLoan();

        return null === $loan ? 0 : $loan->getMonthlyPaymentCents() + $loan->insuranceMonthlyCentsDue();
    }

    /** The rent less the loan's payment: what the box brings, or costs, each month. */
    public function getMonthlyBalanceCents(): int
    {
        return $this->monthlyRentCents - $this->getLoanPaymentCents();
    }

    /** @return array{month: string, netAmountCents: int, targetProjectId: string|null}|null */
    public function getResale(): ?array
    {
        if (null === $this->resaleOn || null === $this->resaleNetCents) {
            return null;
        }

        return [
            'month' => $this->resaleOn->format('Y-m'),
            'netAmountCents' => $this->resaleNetCents,
            'targetProjectId' => null === $this->resaleTarget ? null : (string) $this->resaleTarget->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'projectId' => $this->getProjectId(),
            'priceCents' => $this->getPriceCents(),
            'downPaymentCents' => $this->getDownPaymentCents(),
            'loanId' => $this->getLoanId(),
            'loanPaymentCents' => $this->getLoanPaymentCents(),
            'monthlyRentCents' => $this->monthlyRentCents,
            'monthlyBalanceCents' => $this->getMonthlyBalanceCents(),
            'resale' => $this->getResale(),
        ];
    }
}
