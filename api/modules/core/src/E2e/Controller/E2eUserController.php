<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Controller;

use Maggie\Core\E2e\User\E2eUserFactory;
use Maggie\Core\E2e\User\E2eUserProfile;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Creates the account a journey owns (MAG-577), with the data of a named profile,
 * and signs it in — the answer is the login's, plus the seed manifest of the
 * rows the profile gave it.
 *
 * Shut like the login (E2eAccessGuard; the service exists only under `when@e2e`),
 * and absent from every other environment (E2eSurfaceAbsenceTest).
 */
final class E2eUserController
{
    public function __construct(
        private readonly E2eAccessGuard $guard,
        private readonly E2eUserFactory $userFactory,
        private readonly E2eSessionFactory $sessionFactory,
    ) {
    }

    #[Route('/api/auth/e2e/users', name: 'auth_e2e_users', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        if (null !== $refusal = $this->guard->refuse($request)) {
            return $refusal;
        }

        $data = json_decode((string) $request->getContent(), true);
        $name = \is_array($data) ? ($data['profile'] ?? E2eUserProfile::Standard->value) : null;
        $profile = \is_string($name) ? E2eUserProfile::tryFrom($name) : null;

        if (null === $profile) {
            return new JsonResponse([
                'error' => 'Unknown profile',
                'profiles' => array_map(static fn (E2eUserProfile $case): string => $case->value, E2eUserProfile::cases()),
            ], Response::HTTP_BAD_REQUEST);
        }

        $journeyUser = $this->userFactory->create($profile);

        return $this->sessionFactory->open($journeyUser->user, $request, [
            'profile' => $profile->value,
            'neighbour' => null === $journeyUser->neighbour ? null : [
                'id' => (string) $journeyUser->neighbour->getId(),
                'email' => $journeyUser->neighbour->getEmail(),
                'name' => $journeyUser->neighbour->getName(),
            ],
            'manifest' => [
                'anchor' => $journeyUser->anchor->format(\DATE_ATOM),
                'references' => $journeyUser->references,
            ],
        ]);
    }
}
