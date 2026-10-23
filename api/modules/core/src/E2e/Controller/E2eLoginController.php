<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Controller;

use Maggie\Core\Repository\UserRepository;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Signs a seeded user in without Google.
 *
 * Google's OAuth consent screen cannot be driven by Playwright or Maestro, so
 * the e2e journeys need a door of their own. That door is dangerous by nature,
 * so it is shut three times over:
 *
 * 1. the service is only registered under `when@e2e`, and routes come from
 *    registered controller services (`resource: routing.controllers`), so in
 *    any other environment the route does not exist at all;
 * 2. this method still refuses to run outside `e2e`, which survives someone
 *    importing the service definition by mistake;
 * 3. the caller must present E2E_LOGIN_TOKEN, so a stack booted in `e2e` on a
 *    reachable host is not an open door to every account.
 *
 * Guarantee 1 is what E2eSurfaceAbsenceTest asserts, per MAG-94. Guarantees 2
 * and 3 are E2eAccessGuard's, shared with the door that creates users.
 */
final class E2eLoginController
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly E2eAccessGuard $guard,
        private readonly E2eSessionFactory $sessionFactory,
    ) {
    }

    #[Route('/api/auth/e2e/login', name: 'auth_e2e_login', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        if (null !== $refusal = $this->guard->refuse($request)) {
            return $refusal;
        }

        $data = json_decode((string) $request->getContent(), true);
        $email = \is_array($data) ? ($data['email'] ?? null) : null;

        if (!\is_string($email) || '' === $email) {
            return new JsonResponse(['error' => 'Missing email'], Response::HTTP_BAD_REQUEST);
        }

        $user = $this->userRepository->findOneBy(['email' => $email]);

        if (!$user) {
            // Deliberately not "find or create": a journey that logs in as a
            // user the seed never made is a broken journey, and inventing one
            // would hide that behind a green step. A journey that wants its own
            // account asks for one at /api/auth/e2e/users.
            return new JsonResponse(['error' => 'Unknown user'], Response::HTTP_NOT_FOUND);
        }

        return $this->sessionFactory->open($user, $request);
    }
}
