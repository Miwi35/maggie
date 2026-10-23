<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Repository\ProjectRepository;

/**
 * Makes a project an investment, or replaces what it was (MAG-557): the price,
 * the funding that leaves the loan, the rent and the planned resale. The net
 * proceeds become a `resale` source of the target project, as much as it still
 * needs: what it does not need is cash of the resale month, which the planner
 * gives to the projects waiting.
 */
class SetProjectInvestment
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ProjectRepository $projectRepository,
        private readonly SetProjectFunding $setProjectFunding,
    ) {
    }

    /**
     * @param list<array{kind: FundingSourceKind, amountCents: int, account: ?Account, loan: ?Loan}> $sources checked
     * @param array{month: string, netCents: int, target: ?Project}|null                               $resale  checked
     */
    public function execute(Project $project, int $priceCents, array $sources, int $rentCents, ?array $resale): Project
    {
        $this->em->getConnection()->transactional(function () use ($project, $priceCents, $sources, $rentCents, $resale): void {
            $project->setAmountCents($priceCents);
            $this->setProjectFunding->execute($project, $sources);

            $investment = $project->getInvestment();
            if (null === $investment) {
                $investment = (new ProjectInvestment())->setProject($project);
                $project->setInvestment($investment);
            }
            $investment->setMonthlyRentCents($rentCents);
            $investment->planResale($resale['month'] ?? null, $resale['netCents'] ?? null, $resale['target'] ?? null);

            $this->unsetResaleSources($project);
            $target = $resale['target'] ?? null;
            if (null !== $target) {
                $room = max(0, $target->getAmountCents() - $target->fundedCents());
                $amount = min($resale['netCents'], $room);
                if ($amount > 0) {
                    (new ProjectFundingSource())
                        ->setKind(FundingSourceKind::Resale)
                        ->setAmountCents($amount)
                        ->setSourceProject($project)
                        ->setPosition(\count($target->getFundingSources()))
                        ->setProject($target);
                }
            }

            $this->em->flush();
        });

        return $project;
    }

    /** What this project's resale set aside on the other projects. */
    public function unsetResaleSources(Project $project): void
    {
        foreach ($this->projectRepository->findResaleSourcesOf($project) as $source) {
            $source->getProject()->removeFundingSource($source);
        }
    }
}
