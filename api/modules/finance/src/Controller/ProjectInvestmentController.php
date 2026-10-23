<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\RemoveProjectInvestmentCommand;
use Maggie\Finance\Message\SetProjectInvestmentCommand;
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
 * A project's investment (MAG-557): `PUT` replaces it whole with
 * `{priceCents?, downPaymentCents, loan?, monthlyRentCents, resale?}`, `DELETE`
 * makes the project a plain one again. Answers the project; a refusal answers
 * `error` and `current`, the project as it is.
 */
final class ProjectInvestmentController
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    #[Route('/api/finance/projects/{id}/investment', name: 'api_finance_project_investment_set', methods: ['PUT'])]
    public function set(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $project = $this->projectRepository->findOneOfUser($user, $id);
        if (null === $project) {
            return new JsonResponse(['error' => 'Project not found.'], Response::HTTP_NOT_FOUND);
        }
        $current = ProjectView::of($project);

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body is not JSON.', 'current' => $current], Response::HTTP_BAD_REQUEST);
        }
        if (!\is_array($body) || ([] !== $body && array_is_list($body))) {
            return new JsonResponse(['error' => 'The body must be an object {priceCents?, downPaymentCents, loan?, monthlyRentCents, resale?}.', 'current' => $current], Response::HTTP_BAD_REQUEST);
        }

        return $this->dispatch(new SetProjectInvestmentCommand((string) $user->getId(), $id, $body), $current);
    }

    #[Route('/api/finance/projects/{id}/investment', name: 'api_finance_project_investment_remove', methods: ['DELETE'])]
    public function remove(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $project = $this->projectRepository->findOneOfUser($user, $id);
        if (null === $project) {
            return new JsonResponse(['error' => 'Project not found.'], Response::HTTP_NOT_FOUND);
        }

        return $this->dispatch(new RemoveProjectInvestmentCommand((string) $user->getId(), $id), ProjectView::of($project));
    }

    /** @param array<string, mixed> $current */
    private function dispatch(SetProjectInvestmentCommand|RemoveProjectInvestmentCommand $command, array $current): JsonResponse
    {
        try {
            /** @var Project $project */
            $project = $this->bus->dispatch($command)->last(HandledStamp::class)?->getResult();
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
