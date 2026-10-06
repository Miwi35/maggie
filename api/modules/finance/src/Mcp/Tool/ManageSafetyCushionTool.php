<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Maggie\Finance\UseCase\GetCushionStatus;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_safety_cushion', description: 'Read or configure the safety cushion — the emergency fund that must be full before any investment or project starts. The status action reports its state (building, complete, recharging), its target (targetMonths x monthly net income), what the cushion accounts currently hold, the deficit, and the monthly recharge plan. Its current amount is never set by hand: it is the balance of the accounts flagged as cushion. The configure action sets targetMonths, monthlyNetIncomeCents, rechargeCapCents (the most that may go back in per month) and rechargeTargetMonths (the wished-for recharge horizon; when the cap is lower, the recharge simply takes longer). All amounts are integer cents.')]
class ManageSafetyCushionTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly SafetyCushionRepository $cushionRepository,
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
    ): string {
        try {
            return match ($action) {
                'status' => $this->status(),
                'configure' => $this->configure($targetMonths, $monthlyNetIncomeCents, $rechargeCapCents, $rechargeTargetMonths),
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

    private function configure(?int $targetMonths, ?int $monthlyNetIncomeCents, ?int $rechargeCapCents, ?int $rechargeTargetMonths): string
    {
        if (null === $targetMonths
            && null === $monthlyNetIncomeCents
            && null === $rechargeCapCents
            && null === $rechargeTargetMonths
        ) {
            return json_encode(['error' => 'configure needs at least one of targetMonths, monthlyNetIncomeCents, rechargeCapCents or rechargeTargetMonths.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $cushion = $this->cushionRepository->findOneByUser($user);

        if (null === $cushion) {
            // Reading the status creates the cushion on first use.
            $this->getCushionStatus->execute($user);
            $cushion = $this->cushionRepository->findOneByUser($user);
        }

        /** @var SafetyCushion $cushion */
        $stamped = $this->bus->dispatch(new UpdateSafetyCushionCommand(
            userId: (string) $user->getId(),
            safetyCushionId: (string) $cushion->getId(),
            targetMonths: $targetMonths,
            monthlyNetIncomeCents: $monthlyNetIncomeCents,
            rechargeCapCents: $rechargeCapCents,
            rechargeTargetMonths: $rechargeTargetMonths,
        ));

        $stamped->last(HandledStamp::class)->getResult();

        return json_encode([
            'success' => true,
            'cushion' => $this->getCushionStatus->execute($user),
        ], JSON_THROW_ON_ERROR);
    }
}
