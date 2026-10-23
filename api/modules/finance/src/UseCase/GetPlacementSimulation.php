<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Repository\ScenarioRepository;
use Maggie\Finance\Service\PlacementProjection;
use Maggie\Finance\Service\PlacementSimulator;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioCatalog;
use Maggie\Finance\Service\ScenarioSpec;

/**
 * A placement month by month, alone and with what the projects take out of it
 * (MAG-556): `{final, paid, interest, rent, curve}` (MAG-555) plus `withdrawals`,
 * `curveWithout`, `curveWith`, `gapAtEnd`, `finalWith`, `rentWith`, `withdrawn`.
 *
 * The projects are those of the scenario asked for, else of the current one, else
 * all the user's, in their order; each `capital` source naming the placement
 * leaves it the month its project is bought.
 */
class GetPlacementSimulation
{
    public function __construct(
        private readonly PlacementRepository $placementRepository,
        private readonly ScenarioRepository $scenarioRepository,
        private readonly ScenarioCatalog $catalog,
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly MeasureIndependenceBaseline $measureIndependenceBaseline,
        private readonly ScenarioCalculator $calculator,
        private readonly PlacementProjection $projection,
        private readonly PlacementSimulator $simulator,
    ) {
    }

    /**
     * @return array<string, mixed>|null null when the placement is not the user's
     *
     * @throws ScenarioNotFound when the scenario is not the user's
     */
    public function execute(User $user, string $placementId, ?string $scenarioId = null): ?array
    {
        $placement = $this->placementRepository->findOneOfUser($user, $placementId);
        if (null === $placement) {
            return null;
        }

        $inputs = $this->readPlanningInputs->execute($user);
        if (null !== $scenarioId) {
            $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];
            $spec = ($this->catalog->find($user, $scenarioId, $inputs, $base) ?? throw new ScenarioNotFound($scenarioId))->spec();
        } else {
            $current = $this->scenarioRepository->findCurrent($user);
            $spec = null === $current ? ScenarioSpec::ofProjects($inputs->projects) : ScenarioSpec::fromScenario($current);
        }

        $plan = $this->calculator->plan($inputs, $spec)['plan'];

        return $this->simulator->of($placement) + $this->projection->figures($placement, $plan, $inputs->projectsById());
    }
}
