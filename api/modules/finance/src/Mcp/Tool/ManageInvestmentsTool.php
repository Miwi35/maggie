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
#[McpTool(name: 'manage_investments', description: 'Makes a project an investment, only for a project that yields money; a savings simulation is manage_placements. Actions: get, set, remove; all need projectId. get returns the investment (null if none). set takes the WHOLE investment, replacing the current one, in integer cents: downPaymentCents (required, 0 allowed; loan = price minus it), monthlyRentCents (required, 0 allowed), priceCents (optional), loan (required unless the down payment is the whole price or the project already has a loan source): {annualRateBasisPoints, durationMonths}; resale (optional, null = none): {month YYYY-MM, netAmountCents, targetProjectId?}. remove keeps the project and its funding. A missing value is refused with the list of what is missing, plus current.')]
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
