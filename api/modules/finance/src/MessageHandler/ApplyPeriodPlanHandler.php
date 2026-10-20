<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Exception\PeriodPlanRejected;
use Maggie\Finance\Message\ApplyPeriodPlanCommand;
use Maggie\Finance\UseCase\ApplyPeriodPlan;
use Maggie\Finance\UseCase\PlanningPeriod;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ApplyPeriodPlanHandler
{
    public function __construct(
        private readonly ApplyPeriodPlan $applyPeriodPlan,
        private readonly UserRepository $userRepository,
    ) {
    }

    /** @return list<array{index: int, kind: string, created: int, updated: int, ids: list<string>}> */
    public function __invoke(ApplyPeriodPlanCommand $command): array
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        try {
            $period = PlanningPeriod::parse($command->period);
        } catch (\InvalidArgumentException $e) {
            throw new PeriodPlanRejected($e->getMessage(), null, $e);
        }

        return $this->applyPeriodPlan->execute($user, $period, $command->items);
    }
}
