<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\ScenarioRepository;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioSpec;

/**
 * The user's projects over the next 36 months (MAG-526): capacity first, the
 * safety cushion first. Recomputed on every read, never stored — the clients
 * read it again when a project, a loan, the cushion, an account or a scenario
 * is published.
 *
 * It follows the current scenario when the user chose one (MAG-550): its
 * projects, in its order, its monthly saving and its early repayments. With
 * none, every project is planned in the user's priority order.
 *
 * `$overspentCents` is what the month under way already went over: it lowers
 * the capacity of this month only, so the planning shifts without blame.
 */
class GetProjectPlanning
{
    public function __construct(
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly ScenarioRepository $scenarioRepository,
        private readonly ScenarioCalculator $calculator,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $overspentCents = 0): array
    {
        $inputs = $this->readPlanningInputs->execute($user);

        $current = $this->scenarioRepository->findCurrent($user);
        $spec = null === $current ? ScenarioSpec::ofProjects($inputs->projects) : ScenarioSpec::fromScenario($current);

        $plan = $this->calculator->plan($inputs, $spec, max(0, $overspentCents))['plan'];

        return [
            'startMonth' => $plan['startMonth'],
            'horizonMonths' => $plan['horizonMonths'],
            'currency' => ($inputs->projects[0] ?? null)?->getCurrency() ?? 'EUR',
            'netCapacityCents' => $plan['netCapacityCents'],
            'isIncomeKnown' => $plan['isIncomeKnown'],
            'cushion' => $plan['cushion'],
            'months' => $plan['months'],
            'projects' => $plan['projects'],
        ];
    }
}
