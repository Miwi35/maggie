<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Message\CreatePlacementCommand;
use Maggie\Finance\Service\PlacementRules;
use Maggie\Finance\UseCase\SavePlacement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreatePlacementHandler
{
    public function __construct(
        private readonly SavePlacement $savePlacement,
        private readonly UserRepository $userRepository,
        private readonly PlacementRules $rules,
    ) {
    }

    public function __invoke(CreatePlacementCommand $command): Placement
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $this->rules->assertName($command->name);
        $frequency = $this->rules->frequency($command->frequency);
        $this->rules->assert($command->initial, $command->deposit, $command->annualRate, $command->years);

        $placement = new Placement();
        $placement->setUser($user);
        $placement->setName($command->name);
        $placement->setInitial($command->initial);
        $placement->setDeposit($command->deposit);
        $placement->setFrequency($frequency);
        $placement->setAnnualRate($command->annualRate);
        $placement->setYears($command->years);

        return $this->savePlacement->execute($placement);
    }
}
