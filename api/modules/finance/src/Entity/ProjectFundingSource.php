<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Finance\Enum\FundingSourceKind;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

/**
 * One source of a project's money (MAG-524): 1 000 € from a savings account,
 * capital freed from an investment (optionally the simulated placement it leaves), or a projected loan. Only ever shown inside
 * its project, published and indexed as a change to it.
 *
 * A `loan` source owns its projected loan: removing the source removes the loan.
 * A `resale` source stands for the planned resale of another project (its
 * investment, MAG-557): it is posed and removed with that investment.
 */
#[ORM\Entity]
#[AggregateRoot('project')]
class ProjectFundingSource
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[Ignore]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Project::class, inversedBy: 'fundingSources')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Ignore]
    private Project $project;

    #[ORM\Column(length: 16, enumType: FundingSourceKind::class)]
    private FundingSourceKind $kind = FundingSourceKind::Savings;

    #[ORM\Column(type: 'integer')]
    private int $amountCents = 0;

    /** The account a `savings` or `capital` source draws on. Served as `accountId`. */
    #[ORM\ManyToOne(targetEntity: Account::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[Ignore]
    private ?Account $account = null;

    /** The simulated placement a `capital` source takes its money from (MAG-556). Served as `placementId`. */
    #[ORM\ManyToOne(targetEntity: Placement::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[Ignore]
    private ?Placement $placement = null;

    /** The projected loan of a `loan` source. Served as `loanId`. */
    #[ORM\OneToOne(targetEntity: Loan::class, cascade: ['persist', 'remove'])]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[Ignore]
    private ?Loan $loan = null;

    /** The project whose resale a `resale` source stands for. Served as `sourceProjectId`. */
    #[ORM\ManyToOne(targetEntity: Project::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[Ignore]
    private ?Project $sourceProject = null;

    #[ORM\Column(options: ['default' => 0])]
    #[Ignore]
    private int $position = 0;

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

    /** Both sides at once: the project's salary part reads its sources. */
    public function setProject(Project $project): static
    {
        $this->project = $project;
        $project->attachFundingSource($this);

        return $this;
    }

    public function getKind(): FundingSourceKind
    {
        return $this->kind;
    }

    public function setKind(FundingSourceKind $kind): static
    {
        $this->kind = $kind;

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

    public function getAccount(): ?Account
    {
        return $this->account;
    }

    public function setAccount(?Account $account): static
    {
        $this->account = $account;

        return $this;
    }

    public function getAccountId(): ?string
    {
        return null === $this->account ? null : (string) $this->account->getId();
    }

    public function getPlacement(): ?Placement
    {
        return $this->placement;
    }

    public function setPlacement(?Placement $placement): static
    {
        $this->placement = $placement;

        return $this;
    }

    public function getPlacementId(): ?string
    {
        return null === $this->placement ? null : (string) $this->placement->getId();
    }

    public function getLoan(): ?Loan
    {
        return $this->loan;
    }

    public function setLoan(?Loan $loan): static
    {
        $this->loan = $loan;

        return $this;
    }

    public function getLoanId(): ?string
    {
        return null === $this->loan ? null : (string) $this->loan->getId();
    }

    public function getSourceProject(): ?Project
    {
        return $this->sourceProject;
    }

    public function setSourceProject(?Project $sourceProject): static
    {
        $this->sourceProject = $sourceProject;

        return $this;
    }

    public function getSourceProjectId(): ?string
    {
        return null === $this->sourceProject ? null : (string) $this->sourceProject->getId();
    }

    public function setPosition(int $position): static
    {
        $this->position = $position;

        return $this;
    }

    /** @return array{kind: string, amountCents: int, accountId: string|null, placementId: string|null, loanId: string|null, sourceProjectId: string|null} */
    public function toArray(): array
    {
        return [
            'kind' => $this->kind->value,
            'amountCents' => $this->amountCents,
            'accountId' => $this->getAccountId(),
            'placementId' => $this->getPlacementId(),
            'loanId' => $this->getLoanId(),
            'sourceProjectId' => $this->getSourceProjectId(),
        ];
    }
}
