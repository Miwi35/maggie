<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Service\ProjectFundingRules;

/**
 * Replaces a project's funding sources (MAG-524). The loan source keeps its
 * projected loan — same id, new amount or conditions —; dropping it removes
 * the loan.
 */
class SetProjectFunding
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @param list<array{kind: FundingSourceKind, amountCents: int, account: ?Account, loan: ?Loan}> $sources checked
     */
    public function execute(Project $project, array $sources): Project
    {
        $this->em->getConnection()->transactional(function () use ($project, $sources): void {
            $loanSource = $project->loanSource();

            foreach ($project->getFundingSources() as $current) {
                if ($current !== $loanSource) {
                    $project->removeFundingSource($current);
                }
            }

            $keepsLoan = false;
            foreach ($sources as $position => $wanted) {
                if (FundingSourceKind::Loan === $wanted['kind'] && null !== $wanted['loan']) {
                    $source = $loanSource ?? (new ProjectFundingSource())->setKind(FundingSourceKind::Loan)->setProject($project);
                    $loan = $source->getLoan();
                    if (null === $loan) {
                        $source->setLoan($wanted['loan']);
                    } else {
                        ProjectFundingRules::copy($wanted['loan'], $loan);
                    }
                    $source->setAmountCents($wanted['amountCents'])->setPosition($position);
                    $keepsLoan = true;
                    continue;
                }

                (new ProjectFundingSource())
                    ->setKind($wanted['kind'])
                    ->setAmountCents($wanted['amountCents'])
                    ->setAccount($wanted['account'])
                    ->setPosition($position)
                    ->setProject($project);
            }

            if (null !== $loanSource && !$keepsLoan) {
                $project->removeFundingSource($loanSource);
                if (null !== $loan = $loanSource->getLoan()) {
                    $this->em->remove($loan);
                }
            }

            $this->em->flush();
        });

        return $project;
    }
}
