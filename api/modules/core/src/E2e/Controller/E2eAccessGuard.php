<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Controller;

use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * The two locks every e2e door shares (see E2eLoginController): not outside
 * `e2e`, and not without E2E_LOGIN_TOKEN. An unset token fails closed.
 */
final class E2eAccessGuard
{
    public function __construct(
        private readonly string $environment,
        private readonly string $e2eLoginToken,
    ) {
    }

    /** @return JsonResponse|null the refusal, or null when the request may go on */
    public function refuse(Request $request): ?JsonResponse
    {
        if ('e2e' !== $this->environment) {
            throw new NotFoundHttpException();
        }

        if ('' === $this->e2eLoginToken || !hash_equals($this->e2eLoginToken, (string) $request->headers->get('X-E2E-Token'))) {
            return new JsonResponse(['error' => 'Invalid e2e token'], Response::HTTP_UNAUTHORIZED);
        }

        return null;
    }
}
