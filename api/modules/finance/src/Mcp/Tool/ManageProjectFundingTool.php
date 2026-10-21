<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\SetProjectFundingCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectView;
use Maggie\Finance\UseCase\GetProjectOptions;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial or refused one answers
 * `error` plus `current`, the project as it is, so the next call can be whole
 * (MAG-524).
 */
#[McpTool(name: 'manage_project_funding', description: 'Set where a project\'s money comes from, or price its options. A project is funded by its sources, the rest is saved on the salary (salaryCents = amountCents minus the sources, never below 0). set takes projectId and sources = the WHOLE list, which replaces the current one ([] removes them all): each source is {kind, amountCents (integer cents, > 0), account?, loan?}. kind savings: savings outside the safety cushion, account = the id of a savings or checking account that is not the cushion. kind capital: capital freed from an investment, account = the id of an investment account. kind loan: a projected loan, loan = its conditions: annualRateBasisPoints (BASIS POINTS, 400 = 4 %) and durationMonths are required; optional type (amortizing_fixed by default, amortizing_variable, in_fine, stepped, deferred_partial, deferred_total, bridge, with what each type requires as in manage_loans), insuranceMonthlyCents or insuranceRateBasisPoints, feesCents, aprBasisPoints, deferralMonths, prepaymentPenaltyMode, lender, name. A loan source creates a loan with projected = true of amountCents, kept out of the timeline and the saving capacity; removing the source removes that loan; a loan source sent without loan keeps the conditions it has. At most one loan source; the sources cannot add up to more than the project\'s amount. options takes projectId and prices three options on the project\'s amount alone: cash (saved on the net saving capacity), loan (the whole amount borrowed, available now) and partial (downPaymentCents saved first, the rest borrowed). Each gives totalCostCents, netCostCents (interest + insurance + fees minus yieldCents, what the capital kept would earn at yieldBasisPoints compounded monthly over the loan — it can be negative), monthlyPaymentCents (insurance aside), durationMonths, availableInMonths (months of net saving capacity before the money is there; null when the capacity is 0 or less), capitalKeptCents and nImpactMonths (always null for now). The loan\'s conditions come from the project\'s loan source; loanRateBasisPoints and loanMonths override them, and are required without one. yieldBasisPoints is required (0 allowed); downPaymentCents is required without a loan source (with one: the amount not borrowed). Report the figures as facts: never say one option is better. Nothing is deduced: a missing value is refused with the list of what is missing — ask the user. A refused request answers error plus current.')]
class ManageProjectFundingTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProjectRepository $projectRepository,
        private readonly GetProjectOptions $getProjectOptions,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param array<mixed>|null $sources a list of {kind, amountCents, account?, loan?} */
    public function __invoke(
        string $action,
        ?string $projectId = null,
        ?array $sources = null,
        ?int $loanRateBasisPoints = null,
        ?int $loanMonths = null,
        ?int $downPaymentCents = null,
        ?int $yieldBasisPoints = null,
    ): string {
        try {
            if (!\in_array($action, ['set', 'options'], true)) {
                return self::json(['error' => "Unknown action: {$action}. Use set or options."]);
            }

            if (null === $projectId) {
                return self::json(['error' => "projectId is required for {$action}: list the projects with manage_projects."]);
            }

            $user = $this->userContext->requireUser();
            $project = $this->projectRepository->findOneOfUser($user, $projectId);
            if (null === $project) {
                return self::json(['error' => "Project not found: {$projectId}"]);
            }
            // Read before the handler: the state a refusal was made against.
            $current = ProjectView::of($project);

            if ('options' === $action) {
                try {
                    return self::json($this->getProjectOptions->execute($project, $user, $loanRateBasisPoints, $loanMonths, $downPaymentCents, $yieldBasisPoints));
                } catch (\DomainException $e) {
                    return self::json(['error' => $e->getMessage(), 'current' => $current]);
                }
            }

            if (null === $sources) {
                return self::json(['error' => 'sources is required for set: the whole list of {kind, amountCents, account?, loan?} ([] removes them all).', 'current' => $current]);
            }

            try {
                /** @var Project $project */
                $project = $this->bus->dispatch(new SetProjectFundingCommand(
                    userId: (string) $user->getId(),
                    projectId: $projectId,
                    sources: array_values($sources),
                ))->last(HandledStamp::class)?->getResult();
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
