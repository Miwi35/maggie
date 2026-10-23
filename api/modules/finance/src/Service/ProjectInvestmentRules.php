<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Repository\ProjectRepository;

/**
 * What an investment is held to whichever door it comes through (API, MCP,
 * MAG-557). Every refusal is a DomainException; nothing is deduced — the down
 * payment and the rent are asked for, and so are the loan's conditions when
 * the project has none yet.
 */
class ProjectInvestmentRules
{
    private const FIELDS = ['priceCents', 'downPaymentCents', 'loan', 'monthlyRentCents', 'resale'];
    private const RESALE_FIELDS = ['month', 'netAmountCents', 'targetProjectId'];

    public function __construct(
        private readonly ProjectFundingRules $funding,
        private readonly ProjectRepository $projects,
    ) {
    }

    /**
     * Everything is checked before anything is written. The loan is the part of
     * the price the down payment leaves; the other sources of the project and
     * the resales it receives are part of that down payment.
     *
     * @param array<string, mixed> $data
     *
     * @return array{priceCents: int, sources: list<array{kind: FundingSourceKind, amountCents: int, account: ?Account, loan: ?Loan}>, rentCents: int, resale: array{month: string, netCents: int, target: ?Project}|null}
     */
    public function check(Project $project, User $user, array $data): array
    {
        $unknown = array_diff(array_keys($data), self::FIELDS);
        if ([] !== $unknown) {
            throw new \DomainException('Unknown investment field: '.implode(', ', $unknown).'. Expected: '.implode(', ', self::FIELDS).'.');
        }

        $missing = array_values(array_filter(['downPaymentCents', 'monthlyRentCents'], static fn (string $field): bool => !isset($data[$field])));
        if ([] !== $missing) {
            throw new \DomainException('The investment is missing: '.implode(', ', $missing).'. Ask for them: nothing is deduced (0 is a value).');
        }

        $price = $data['priceCents'] ?? $project->getAmountCents();
        $this->assertCents('priceCents', $price, 1);
        $down = $data['downPaymentCents'];
        $this->assertCents('downPaymentCents', $down, 0);
        $rent = $data['monthlyRentCents'];
        $this->assertCents('monthlyRentCents', $rent, 0);
        /** @var int $price */
        /** @var int $down */
        /* @var int $rent */
        if ($down > $price) {
            throw new \DomainException(\sprintf('downPaymentCents (%d) is more than the price (%d).', $down, $price));
        }

        $borrowed = $price - $down;
        $conditions = $data['loan'] ?? null;
        if (0 === $borrowed && null !== $conditions) {
            throw new \DomainException('loan is given but the down payment is the whole price: nothing is borrowed.');
        }

        $others = [];
        $heldByOthers = 0;
        foreach ($project->getFundingSources() as $source) {
            if (FundingSourceKind::Loan === $source->getKind()) {
                continue;
            }
            $heldByOthers += $source->getAmountCents();
            if (FundingSourceKind::Resale !== $source->getKind()) {
                $others[] = ['kind' => $source->getKind()->value, 'amountCents' => $source->getAmountCents(), 'account' => $source->getAccountId(), 'placement' => $source->getPlacementId()];
            }
        }
        if ($heldByOthers > $down) {
            throw new \DomainException(\sprintf('The project\'s other sources (savings, capital, resales) add up to %d cents, more than the down payment %d: raise the down payment or lower them with manage_project_funding.', $heldByOthers, $down));
        }

        $sources = $others;
        if ($borrowed > 0) {
            $sources[] = ['kind' => FundingSourceKind::Loan->value, 'amountCents' => $borrowed, 'loan' => $conditions];
        }

        return [
            'priceCents' => $price,
            'sources' => $this->funding->sources($project, $user, $sources, $price),
            'rentCents' => $rent,
            'resale' => $this->resale($project, $user, $data['resale'] ?? null),
        ];
    }

    /**
     * The resale of an investment, `month` (YYYY-MM) and the net amount
     * (after the loan is repaid and the fees), and the project it is set aside
     * for — none: it speeds up the projects waiting that month.
     *
     * @return array{month: string, netCents: int, target: ?Project}|null
     */
    private function resale(Project $project, User $user, mixed $resale): ?array
    {
        if (null === $resale) {
            return null;
        }
        if (!\is_array($resale) || ([] !== $resale && array_is_list($resale))) {
            throw new \DomainException('resale must be an object {month, netAmountCents, targetProjectId?}, or null for no resale.');
        }
        $unknown = array_diff(array_keys($resale), self::RESALE_FIELDS);
        if ([] !== $unknown) {
            throw new \DomainException('Unknown resale field: '.implode(', ', $unknown).'.');
        }

        $month = $resale['month'] ?? null;
        if (!\is_string($month) || 1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
            throw new \DomainException('resale.month must be a month as YYYY-MM.');
        }
        $net = $resale['netAmountCents'] ?? null;
        $this->assertCents('resale.netAmountCents', $net, 1);
        /** @var int $net */
        $target = null;
        if (null !== ($resale['targetProjectId'] ?? null)) {
            $id = \is_string($resale['targetProjectId']) ? basename($resale['targetProjectId']) : '';
            $target = $this->projects->findOneOfUser($user, $id)
                ?? throw new \DomainException('Project not found: '.(\is_string($resale['targetProjectId']) ? $id : 'targetProjectId must be a project id'));
            if ($target === $project) {
                throw new \DomainException('An investment\'s resale cannot be set aside for the investment itself.');
            }
            $this->assertNoCycle($project, $target, $user);
        }

        return ['month' => $month, 'netCents' => $net, 'target' => $target];
    }

    /** The target waits for the resale: it cannot be something the investment itself waits for, however far. */
    private function assertNoCycle(Project $project, Project $target, User $user): void
    {
        $byId = [];
        foreach ($this->projects->findByUser($user) as $candidate) {
            $byId[(string) $candidate->getId()] = $candidate;
        }

        $seen = [];
        $queue = [$project];
        while ([] !== $queue) {
            $current = array_pop($queue);
            foreach ($this->waitsFor($current) as $id) {
                if ($id === (string) $target->getId()) {
                    throw new \DomainException(\sprintf('%s waits for %s, directly or through other projects: its resale cannot pay for %s.', $project->getName(), $target->getName(), $target->getName()));
                }
                if (!isset($seen[$id]) && isset($byId[$id])) {
                    $seen[$id] = true;
                    $queue[] = $byId[$id];
                }
            }
        }
    }

    /** @return list<string> the projects this one waits for: its dependencies and the resales that fund it */
    private function waitsFor(Project $project): array
    {
        $ids = $project->dependencyIds();
        foreach ($project->getFundingSources() as $source) {
            if (FundingSourceKind::Resale === $source->getKind() && null !== $source->getSourceProjectId()) {
                $ids[] = $source->getSourceProjectId();
            }
        }

        return $ids;
    }

    private function assertCents(string $field, mixed $value, int $min): void
    {
        if (!\is_int($value) || $value < $min || $value > ProjectInvestment::MAX_CENTS) {
            throw new \DomainException(\sprintf('%s must be an integer of cents, from %d to %d.', $field, $min, ProjectInvestment::MAX_CENTS));
        }
    }
}
