<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class HomeController
{
    public function __construct(
        private readonly HomeCardRegistry $registry,
        private readonly Security $security,
    ) {
    }

    #[Route('/api/home/{module}', name: 'api_home_card', methods: ['GET'], requirements: ['module' => '[a-z_]+'])]
    public function __invoke(string $module, Request $request): JsonResponse
    {
        // The firewall already refuses an anonymous call; this is the type guard.
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::problem(Response::HTTP_UNAUTHORIZED, 'Unauthorized');
        }

        $day = HomeDay::tryFrom($request->query->getString('day', HomeDay::Today->value));
        if (null === $day) {
            return self::problem(Response::HTTP_BAD_REQUEST, 'Invalid parameter day: expected today or tomorrow.');
        }

        $provider = $this->registry->get($module);
        if (null === $provider) {
            return self::problem(Response::HTTP_NOT_FOUND, sprintf('No home card for module "%s".', $module));
        }

        return new JsonResponse(['module' => $module, 'day' => $day->value] + $provider->state($user, $day));
    }

    private static function problem(int $status, string $detail): JsonResponse
    {
        return new JsonResponse(
            ['type' => 'about:blank', 'title' => Response::$statusTexts[$status], 'status' => $status, 'detail' => $detail],
            $status,
            ['Content-Type' => 'application/problem+json'],
        );
    }
}
