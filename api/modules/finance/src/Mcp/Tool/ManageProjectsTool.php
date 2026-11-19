<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\CreateProjectCommand;
use Maggie\Finance\Message\DeleteProjectCommand;
use Maggie\Finance\Message\ReorderProjectsCommand;
use Maggie\Finance\Message\UpdateProjectCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectView;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial one is refused with
 * `current`, what was given or what the project is, so the next call can be
 * whole.
 */
#[McpTool(name: 'manage_projects', description: 'Actions: list, create, update, delete, reorder. create needs name and amountCents (integer cents > 0). currency: 3-letter code, default EUR. Dates ISO. wellbeing: essential = 4, high = 3, medium = 2 (default), comfort = 1. dateMode: none, movable, fixed, deadline; fixed and deadline require targetOn, and targetOn requires one of them. dependsOn: ids of the projects this one waits for, replaces the whole list; no self-dependency or cycle.position: 0 = first priority, a new project goes last; reorder takes ids = EVERY project id in the new order (a partial list is refused). update changes only provided fields; clear empties note, dependsOn or targetOn. A refusal returns error plus current.')]
class ManageProjectsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ProjectRepository $projectRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param list<string>|null $dependsOn
     * @param list<string>|null $ids
     * @param list<string>|null $clear
     */
    public function __invoke(
        string $action,
        ?string $projectId = null,
        ?string $name = null,
        ?int $amountCents = null,
        ?string $currency = null,
        ?string $wellbeing = null,
        ?string $dateMode = null,
        ?string $targetOn = null,
        ?array $dependsOn = null,
        ?string $note = null,
        ?array $ids = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $amountCents, $currency, $wellbeing, $dateMode, $targetOn, $dependsOn, $note),
                'update' => $this->update($projectId, $name, $amountCents, $currency, $wellbeing, $dateMode, $targetOn, $dependsOn, $note, $clear),
                'delete' => $this->delete($projectId),
                'reorder' => $this->reorder($ids),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update, delete, or reorder."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage()]);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return self::json(['projects' => ProjectView::list($this->projectRepository->findByUser($user))]);
    }

    /** @param list<string>|null $dependsOn */
    private function create(?string $name, ?int $amountCents, ?string $currency, ?string $wellbeing, ?string $dateMode, ?string $targetOn, ?array $dependsOn, ?string $note): string
    {
        $user = $this->userContext->requireUser();

        $given = array_filter([
            'name' => $name,
            'amountCents' => $amountCents,
            'currency' => $currency,
            'wellbeing' => $wellbeing,
            'dateMode' => $dateMode,
            'targetOn' => $targetOn,
            'dependsOn' => $dependsOn,
            'note' => $note,
        ], static fn (mixed $value): bool => null !== $value);

        if (null === $name || null === $amountCents) {
            return self::json(['error' => 'name and amountCents are required for create: ask the user for what is missing.', 'current' => $given]);
        }
        if (\in_array($dateMode, ['fixed', 'deadline'], true) && null === $targetOn) {
            return self::json(['error' => "dateMode {$dateMode} needs a targetOn (YYYY-MM-DD): ask the user for the date.", 'current' => $given]);
        }
        if (null !== $targetOn && !\in_array($dateMode, ['fixed', 'deadline'], true)) {
            return self::json(['error' => 'A targetOn needs dateMode fixed (a locked date) or deadline (at the latest): ask the user which one.', 'current' => $given]);
        }

        try {
            /** @var Project $project */
            $project = $this->bus->dispatch(new CreateProjectCommand(
                userId: (string) $user->getId(),
                name: $name,
                amountCents: $amountCents,
                currency: $currency ?? 'EUR',
                wellbeing: $wellbeing ?? 'medium',
                dateMode: $dateMode ?? 'none',
                targetOn: $targetOn,
                dependsOn: $dependsOn ?? [],
                note: $note,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $given]);
        }

        return self::json(['success' => true, 'project' => ProjectView::of($project)]);
    }

    /**
     * @param list<string>|null $dependsOn
     * @param list<string>|null $clear
     */
    private function update(?string $projectId, ?string $name, ?int $amountCents, ?string $currency, ?string $wellbeing, ?string $dateMode, ?string $targetOn, ?array $dependsOn, ?string $note, ?array $clear): string
    {
        if (null === $projectId) {
            return self::json(['error' => 'projectId is required for update.']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->projectRepository->findOneOfUser($user, $projectId);
        if (null === $before) {
            return self::json(['error' => "Project not found: {$projectId}"]);
        }
        // Read before the handler touches the entity: the state it was refused against.
        $current = ProjectView::of($before);

        try {
            /** @var Project $project */
            $project = $this->bus->dispatch(new UpdateProjectCommand(
                userId: (string) $user->getId(),
                projectId: $projectId,
                name: $name,
                amountCents: $amountCents,
                currency: $currency,
                wellbeing: $wellbeing,
                dateMode: $dateMode,
                targetOn: $targetOn,
                dependsOn: $dependsOn,
                note: $note,
                clearFields: array_values(array_intersect($clear ?? [], UpdateProjectCommand::CLEARABLE)),
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current]);
        }

        return self::json(['success' => true, 'project' => ProjectView::of($project)]);
    }

    private function delete(?string $projectId): string
    {
        if (null === $projectId) {
            return self::json(['error' => 'projectId is required for delete.']);
        }

        $this->bus->dispatch(new DeleteProjectCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            projectId: $projectId,
        ));

        return self::json(['success' => true]);
    }

    /** @param list<string>|null $ids */
    private function reorder(?array $ids): string
    {
        $user = $this->userContext->requireUser();
        $current = array_map(
            static fn (Project $p): array => ['id' => (string) $p->getId(), 'name' => $p->getName(), 'position' => $p->getPosition()],
            $this->projectRepository->findByUser($user),
        );

        if (null === $ids) {
            return self::json(['error' => 'ids is required for reorder: every project id of the user, in the new order.', 'current' => $current]);
        }

        try {
            /** @var list<Project> $projects */
            $projects = $this->bus->dispatch(new ReorderProjectsCommand(
                userId: (string) $user->getId(),
                ids: $ids,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current]);
        }

        return self::json(['success' => true, 'projects' => ProjectView::list($projects)]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
