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

#[McpTool(name: 'manage_safety_cushion', description: 'Reads or configures the safety cushion (emergency fund filled before projects and investments). Action status: state (building, complete, recharging), target (targetMonths x monthly net income), holding (balance of cushion-flagged accounts less peerClaimsCents, what others owe the owner), deficit, monthly recharge plan; the amount is never set by hand. Planning: below criticalMonths of spending (criticalCents) all saving capacity goes to the cushion; above, cushionSharePercent % (capped at the deficit), the rest to projects and investments; once full, all to them. Action configure sets any of targetMonths, monthlyNetIncomeCents, criticalMonths (0-24, default 2), cushionSharePercent (0-100, default 60), rechargeCapCents, rechargeTargetMonths (indicative); only given fields change, at least one required. Amounts are integer cents.')]
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
