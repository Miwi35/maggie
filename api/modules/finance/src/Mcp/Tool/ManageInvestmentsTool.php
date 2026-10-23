<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\RemoveProjectInvestmentCommand;
use Maggie\Finance\Message\SetProjectInvestmentCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectView;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial or refused one answers
 * `error` plus `current`, the project as it is (MAG-557).
 */
#[McpTool(name: 'manage_investments', description: 'Make a project an investment (a rented box, a flat…): a down payment, a loan, a rent that covers the loan then yields, and a planned resale. get takes projectId and answers the investment (null if the project is not one) with the monthly payment, the rent less the payment and the resale. set takes projectId and the WHOLE investment, which replaces the current one: downPaymentCents (required, 0 allowed; the loan is the price less the down payment), monthlyRentCents (required, 0 allowed), priceCents (optional: the project\'s amount, which it changes), loan (optional when the project already has a loan source, required otherwise unless the down payment is the whole price): annualRateBasisPoints (BASIS POINTS, 390 = 3.9 %) and durationMonths are required, optional type, insuranceMonthlyCents or insuranceRateBasisPoints, feesCents, deferralMonths, lender, name as in manage_project_funding; resale (optional, absent or null: no resale): {month (YYYY-MM), netAmountCents (what is left after the loan is repaid and the fees), targetProjectId? (the project the money is set aside for: its funding gets a resale source, and it is not bought before the resale; without it the money speeds up the projects waiting that month)}. The rent counts in the monthly capacity from the month after the purchase until the resale; the loan stops at the resale. remove takes projectId: the project stays, with its funding, and the resale it set aside is taken back. Nothing is deduced: a missing value is refused with the list of what is missing — ask the user. The planning (get_project_planning) shows the loan, the rent and the resale point. A refused request answers error plus current.')]
class ManageInvestmentsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProjectRepository $projectRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param array<string, mixed>|null $loan
     * @param array<string, mixed>|null $resale
     */
    public function __invoke(
        string $action,
        ?string $projectId = null,
        ?int $priceCents = null,
        ?int $downPaymentCents = null,
        ?int $monthlyRentCents = null,
        ?array $loan = null,
        ?array $resale = null,
    ): string {
        try {
            if (!\in_array($action, ['get', 'set', 'remove'], true)) {
                return self::json(['error' => "Unknown action: {$action}. Use get, set or remove."]);
            }
            if (null === $projectId) {
                return self::json(['error' => "projectId is required for {$action}: list the projects with manage_projects."]);
            }

            $user = $this->userContext->requireUser();
            $project = $this->projectRepository->findOneOfUser($user, $projectId);
            if (null === $project) {
                return self::json(['error' => "Project not found: {$projectId}"]);
            }
            $current = ProjectView::of($project);

            if ('get' === $action) {
                return self::json(['projectId' => $projectId, 'investment' => $current['investment']]);
            }

            $command = 'set' === $action
                ? new SetProjectInvestmentCommand((string) $user->getId(), $projectId, array_filter([
                    'priceCents' => $priceCents,
                    'downPaymentCents' => $downPaymentCents,
                    'monthlyRentCents' => $monthlyRentCents,
                    'loan' => $loan,
                    'resale' => $resale,
                ], static fn (mixed $value): bool => null !== $value))
                : new RemoveProjectInvestmentCommand((string) $user->getId(), $projectId);

            try {
                /** @var Project $project */
                $project = $this->bus->dispatch($command)->last(HandledStamp::class)?->getResult();
            } catch (HandlerFailedException $e) {
                return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current]);
            }

            return self::json(['success' => true, 'project' => ProjectView::of($project)]);
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        }
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
