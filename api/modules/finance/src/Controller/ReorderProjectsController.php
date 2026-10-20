<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\ReorderProjectsCommand;
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
 * The user's priority order (MAG-519): `{"ids": [...]}`, every project id,
 * first one first. Answers the projects in their new order.
 */
final class ReorderProjectsController
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/finance/projects/reorder', name: 'api_finance_projects_reorder', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body is not JSON.'], Response::HTTP_BAD_REQUEST);
        }

        $ids = \is_array($body) ? ($body['ids'] ?? null) : null;
        if (!\is_array($ids) || !array_is_list($ids)) {
            return new JsonResponse(['error' => 'Missing required field: ids, the list of every project id in the new order.'], Response::HTTP_BAD_REQUEST);
        }
        foreach ($ids as $id) {
            if (!\is_string($id) || '' === $id) {
                return new JsonResponse(['error' => 'Each id must be a non-empty string.'], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            /** @var list<Project> $projects */
            $projects = $this->bus->dispatch(new ReorderProjectsCommand(
                userId: (string) $user->getId(),
                ids: $ids,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true, 'projects' => ProjectView::list($projects)]);
    }
}
