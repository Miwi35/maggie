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
#[McpTool(name: 'manage_project_funding', description: 'Actions: set, options; both need projectId. set takes sources, the WHOLE list replacing the current one ([] removes all): {kind, amountCents (integer cents > 0), account?, placement?, loan?}. kind savings: account = id of a savings or checking account outside the safety cushion. kind capital: account = id of an investment account; placement = optional simulated placement id. kind loan: creates a projected loan; loan = {annualRateBasisPoints, durationMonths} required, optional manage_loans conditions; omitted loan keeps current conditions. At most one loan source; sources cannot exceed the project\'s amount. options prices cash, loan and partial, each with totalCostCents, netCostCents, monthlyPaymentCents, durationMonths, availableInMonths. Inputs: yieldBasisPoints (required, 0 allowed), loanRateBasisPoints and loanMonths, downPaymentCents (required without a loan source). A missing value is refused with the list of what is missing, plus current.')]
class ManageProjectFundingTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProjectRepository $projectRepository,
        private readonly GetProjectOptions $getProjectOptions,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param array<mixed>|null $sources a list of {kind, amountCents, account?, placement?, loan?} */
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
                return self::json(['error' => 'sources is required for set: the whole list of {kind, amountCents, account?, placement?, loan?} ([] removes them all).', 'current' => $current]);
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
