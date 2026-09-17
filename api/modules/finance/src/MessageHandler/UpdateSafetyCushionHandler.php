<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Maggie\Finance\UseCase\UpdateSafetyCushion;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateSafetyCushionHandler
{
    public function __construct(
        private readonly UpdateSafetyCushion $updateSafetyCushion,
        private readonly SafetyCushionRepository $repository,
    ) {
    }

    public function __invoke(UpdateSafetyCushionCommand $command): SafetyCushion
    {
        $cushion = $this->repository->find($command->safetyCushionId)
            ?? throw new \DomainException("Safety cushion not found: {$command->safetyCushionId}");

        if ($command->targetMonths !== null) {
            if ($command->targetMonths < 1) {
                throw new \DomainException('The cushion target must be at least one month.');
            }
            $cushion->setTargetMonths($command->targetMonths);
        }
        if ($command->monthlyNetIncomeCents !== null) {
            if ($command->monthlyNetIncomeCents < 0) {
                throw new \DomainException('The reference income cannot be negative.');
            }
            $cushion->setMonthlyNetIncomeCents($command->monthlyNetIncomeCents);
        }
        if ($command->rechargeCapCents !== null) {
            if ($command->rechargeCapCents < 0) {
                throw new \DomainException('The recharge cap cannot be negative.');
            }
            $cushion->setRechargeCapCents($command->rechargeCapCents);
        }
        if ($command->rechargeTargetMonths !== null) {
            if ($command->rechargeTargetMonths < 1) {
                throw new \DomainException('The recharge horizon must be at least one month.');
            }
            $cushion->setRechargeTargetMonths($command->rechargeTargetMonths);
        }

        return $this->updateSafetyCushion->execute($cushion);
    }
}
