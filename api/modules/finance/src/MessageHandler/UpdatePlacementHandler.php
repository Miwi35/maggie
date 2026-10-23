<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Message\UpdatePlacementCommand;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Service\PlacementRules;
use Maggie\Finance\UseCase\SavePlacement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the placement as it was.
 */
#[AsMessageHandler]
class UpdatePlacementHandler
{
    public function __construct(
        private readonly SavePlacement $savePlacement,
        private readonly PlacementRepository $placementRepository,
        private readonly PlacementRules $rules,
    ) {
    }

    public function __invoke(UpdatePlacementCommand $command): Placement
    {
        $placement = $this->placementRepository->findOneOfUser($command->userId, $command->placementId)
            ?? throw new \DomainException("Placement not found: {$command->placementId}");

        if (null !== $command->name) {
            $this->rules->assertName($command->name);
        }
        $frequency = null === $command->frequency ? $placement->getFrequency() : $this->rules->frequency($command->frequency);
        $initial = $command->initial ?? $placement->getInitial();
        $deposit = $command->deposit ?? $placement->getDeposit();
        $annualRate = $command->annualRate ?? $placement->getAnnualRate();
        $years = $command->years ?? $placement->getYears();
        $this->rules->assert($initial, $deposit, $annualRate, $years);

        if (null !== $command->name) {
            $placement->setName($command->name);
        }
        $placement->setInitial($initial);
        $placement->setDeposit($deposit);
        $placement->setFrequency($frequency);
        $placement->setAnnualRate($annualRate);
        $placement->setYears($years);

        return $this->savePlacement->execute($placement);
    }
}
