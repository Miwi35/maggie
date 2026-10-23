<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\UseCase\PreviewProject;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * What adding a project would change, before it is added (MAG-553): the body is
 * a project as `POST /api/finance/projects` takes it (name and amountCents
 * required; currency, wellbeing, dateMode, targetOn, dependsOn as ids) plus an
 * optional `scenarioId` (the current scenario when absent). Nothing is written.
 */
final class PreviewProjectController
{
    private const FIELDS = ['name', 'amountCents', 'currency', 'wellbeing', 'dateMode', 'targetOn', 'dependsOn', 'note', 'scenarioId'];

    public function __construct(
        private readonly Security $security,
        private readonly PreviewProject $previewProject,
    ) {
    }

    #[Route('/api/finance/projects/preview', name: 'api_finance_projects_preview', methods: ['POST'])]
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
        if (!\is_array($body) || array_is_list($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object.'], Response::HTTP_BAD_REQUEST);
        }

        $unknown = array_values(array_diff(array_keys($body), self::FIELDS));
        if ([] !== $unknown) {
            return new JsonResponse(['error' => 'Unknown field: '.implode(', ', $unknown).'. This request takes: '.implode(', ', self::FIELDS).'.'], Response::HTTP_BAD_REQUEST);
        }
        if (!\is_string($body['name'] ?? null) || !\is_int($body['amountCents'] ?? null)) {
            return new JsonResponse(['error' => 'name (a string) and amountCents (an integer, in cents) are required.'], Response::HTTP_BAD_REQUEST);
        }
        foreach (['currency', 'wellbeing', 'dateMode', 'targetOn', 'scenarioId'] as $field) {
            if (isset($body[$field]) && !\is_string($body[$field])) {
                return new JsonResponse(['error' => "{$field} must be a string."], Response::HTTP_BAD_REQUEST);
            }
        }
        $dependsOn = $body['dependsOn'] ?? [];
        if (!\is_array($dependsOn) || !array_is_list($dependsOn) || [] !== array_filter($dependsOn, static fn (mixed $id): bool => !\is_string($id))) {
            return new JsonResponse(['error' => 'dependsOn must be a list of project ids.'], Response::HTTP_BAD_REQUEST);
        }

        try {
            return new JsonResponse($this->previewProject->execute(
                $user,
                $body['name'],
                $body['amountCents'],
                $body['currency'] ?? 'EUR',
                $body['wellbeing'] ?? 'medium',
                $body['dateMode'] ?? 'none',
                $body['targetOn'] ?? null,
                $dependsOn,
                $body['scenarioId'] ?? null,
            ));
        } catch (ScenarioNotFound $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_NOT_FOUND);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
