<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\SetProjectFundingCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectView;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * A project's funding sources (MAG-524): `{"sources": [{kind, amountCents,
 * account?, placement?, loan?}]}`, the whole list, replacing the current one. Answers the
 * project; a refusal answers `error` and `current`, the project as it is.
 */
final class ProjectFundingController
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    #[Route('/api/finance/projects/{id}/funding', name: 'api_finance_project_funding', methods: ['PUT'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $project = $this->projectRepository->findOneOfUser($user, $id);
        if (null === $project) {
            return new JsonResponse(['error' => 'Project not found.'], Response::HTTP_NOT_FOUND);
        }
        // Read before the handler: the state the request was refused against.
        $current = ProjectView::of($project);

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body is not JSON.', 'current' => $current], Response::HTTP_BAD_REQUEST);
        }

        $sources = \is_array($body) ? ($body['sources'] ?? null) : null;
        if (!\is_array($sources) || !array_is_list($sources)) {
            return new JsonResponse([
                'error' => 'Missing required field: sources, the whole list of {kind, amountCents, account?, placement?, loan?} ([] removes them all).',
                'current' => $current,
            ], Response::HTTP_BAD_REQUEST);
        }

        try {
            /** @var Project $project */
            $project = $this->bus->dispatch(new SetProjectFundingCommand(
                userId: (string) $user->getId(),
                projectId: $id,
                sources: $sources,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage(), 'current' => $current], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true, 'project' => ProjectView::of($project)]);
    }
}
