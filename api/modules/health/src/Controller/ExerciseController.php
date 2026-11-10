<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Enum\Equipment;
use Maggie\Health\Enum\MovementPattern;
use Maggie\Health\Service\ExerciseCatalog;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The exercise reference data, read only (MAG-774): `/api/health/exercises`
 * (filters `pattern`, `equipment` — comma separated, keeps what can be done with
 * that gear — and `ladder`) and `/api/health/exercise-ladders`, each with its
 * `{slug}` item.
 */
final class ExerciseController
{
    public function __construct(
        private readonly Security $security,
        private readonly ExerciseCatalog $catalog,
    ) {
    }

    #[Route('/api/health/exercises', name: 'api_health_exercises', methods: ['GET'])]
    public function exercises(Request $request): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return $this->unauthorized();
        }

        $pattern = $request->query->get('pattern');
        if (null !== $pattern && null === MovementPattern::tryFrom($pattern)) {
            return new JsonResponse(['error' => 'Unknown pattern.'], Response::HTTP_BAD_REQUEST);
        }

        $equipment = null;
        if ($request->query->has('equipment')) {
            $raw = $request->query->all()['equipment'];
            $raw = \is_array($raw) ? $raw : [$raw];
            $equipment = [];
            foreach ($raw as $item) {
                foreach (explode(',', (string) $item) as $name) {
                    $name = trim($name);
                    if ('' === $name) {
                        continue;
                    }
                    if (null === Equipment::tryFrom($name)) {
                        return new JsonResponse(['error' => \sprintf('Unknown equipment "%s".', $name)], Response::HTTP_BAD_REQUEST);
                    }
                    $equipment[] = $name;
                }
            }
        }

        $ladder = $request->query->get('ladder');
        if (null !== $ladder && null === $this->catalog->ladder($ladder)) {
            return new JsonResponse(['error' => 'Unknown ladder.'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['member' => $this->catalog->exercises($pattern, $equipment, $ladder)]);
    }

    #[Route('/api/health/exercises/{slug}', name: 'api_health_exercise', methods: ['GET'])]
    public function exercise(string $slug): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return $this->unauthorized();
        }

        return $this->orNotFound($this->catalog->exercise($slug));
    }

    #[Route('/api/health/exercise-ladders', name: 'api_health_exercise_ladders', methods: ['GET'])]
    public function ladders(): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return $this->unauthorized();
        }

        return new JsonResponse(['member' => $this->catalog->ladders()]);
    }

    #[Route('/api/health/exercise-ladders/{slug}', name: 'api_health_exercise_ladder', methods: ['GET'])]
    public function ladder(string $slug): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return $this->unauthorized();
        }

        return $this->orNotFound($this->catalog->ladder($slug));
    }

    /** @param array<string, mixed>|null $item */
    private function orNotFound(?array $item): JsonResponse
    {
        return null === $item
            ? new JsonResponse(['error' => 'Not found.'], Response::HTTP_NOT_FOUND)
            : new JsonResponse($item);
    }

    private function unauthorized(): JsonResponse
    {
        return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
    }
}
