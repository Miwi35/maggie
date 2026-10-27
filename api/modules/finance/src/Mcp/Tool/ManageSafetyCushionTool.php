<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Maggie\Finance\UseCase\EnsureSafetyCushion;
use Maggie\Finance\UseCase\GetCushionStatus;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_safety_cushion', description: 'Read or configure the safety cushion — the emergency fund the saving fills before the projects and investments. The status action reports its state (building, complete, recharging), its target (targetMonths x monthly net income), what the cushion currently holds (the balance of the cushion accounts less peerClaimsCents, what friends and family still owe the owner — it fills back up with every repayment; a debt the owner owes never raises it), the deficit, and the monthly recharge plan. Its current amount is never set by hand: it is the balance of the accounts flagged as cushion. How the planning fills it, building or recharging alike: while it holds less than criticalMonths months of spending (criticalCents, the train de vie measured over three months), the whole saving capacity goes to it and no project is funded; past that threshold, cushionSharePercent % of the capacity goes to it (never more than the deficit) and the rest to the projects, placements and investments; once full, everything goes to them. The configure action sets targetMonths (months of net income), monthlyNetIncomeCents, criticalMonths (0 to 24, default 2), cushionSharePercent (0 to 100, default 60; 100 = cushion first), rechargeCapCents and rechargeTargetMonths (the indicative recharge plan of the status, no longer read by the planning). Only the fields given change; never deduce one the user did not give. All amounts are integer cents.')]
class ManageSafetyCushionTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly EnsureSafetyCushion $ensureSafetyCushion,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?int $targetMonths = null,
        ?int $monthlyNetIncomeCents = null,
        ?int $rechargeCapCents = null,
        ?int $rechargeTargetMonths = null,
        ?int $criticalMonths = null,
        ?int $cushionSharePercent = null,
    ): string {
        try {
            return match ($action) {
                'status' => $this->status(),
                'configure' => $this->configure($targetMonths, $monthlyNetIncomeCents, $rechargeCapCents, $rechargeTargetMonths, $criticalMonths, $cushionSharePercent),
                default => json_encode(['error' => "Unknown action: {$action}. Use status or configure."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function status(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode($this->getCushionStatus->execute($user), JSON_THROW_ON_ERROR);
    }

    private function configure(?int $targetMonths, ?int $monthlyNetIncomeCents, ?int $rechargeCapCents, ?int $rechargeTargetMonths, ?int $criticalMonths, ?int $cushionSharePercent): string
    {
        if (null === $targetMonths
            && null === $monthlyNetIncomeCents
            && null === $rechargeCapCents
            && null === $rechargeTargetMonths
            && null === $criticalMonths
            && null === $cushionSharePercent
        ) {
            return json_encode(['error' => 'configure needs at least one of targetMonths, monthlyNetIncomeCents, criticalMonths, cushionSharePercent, rechargeCapCents or rechargeTargetMonths.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $cushion = $this->ensureSafetyCushion->execute($user);

        $stamped = $this->bus->dispatch(new UpdateSafetyCushionCommand(
            userId: (string) $user->getId(),
            safetyCushionId: (string) $cushion->getId(),
            targetMonths: $targetMonths,
            monthlyNetIncomeCents: $monthlyNetIncomeCents,
            rechargeCapCents: $rechargeCapCents,
            rechargeTargetMonths: $rechargeTargetMonths,
            criticalMonths: $criticalMonths,
            cushionSharePercent: $cushionSharePercent,
        ));

        $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'cushion' => $this->getCushionStatus->execute($user),
        ], JSON_THROW_ON_ERROR);
    }
}
