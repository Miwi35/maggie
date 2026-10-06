<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Catch-up pass over the history: pairs the movements that went from one of
 * the user's accounts to another, as `apply-categorization-rules` files what
 * a new rule now claims.
 *
 * `dryRun` reports the pairs it would write without writing any of them —
 * seeing the lines before four figures change is the point.
 */
final class DetectInternalTransfersController
{
    public function __construct(
        private readonly Security $security,
        private readonly DetectInternalTransfers $detectInternalTransfers,
    ) {
    }

    #[Route('/api/finance/internal-transfers/detect', name: 'api_finance_detect_internal_transfers', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();

        try {
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $limitDays = $body['limitDays'] ?? null;
        if (null !== $limitDays && (!\is_int($limitDays) || $limitDays < 0)) {
            return new JsonResponse(
                ['error' => 'limitDays must be a positive integer'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        // Not coerced: `"false"` is truthy, and a dry run that writes is the
        // one mistake this parameter exists to prevent.
        $dryRun = $body['dryRun'] ?? false;
        if (!\is_bool($dryRun)) {
            return new JsonResponse(['error' => 'dryRun must be a boolean'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(
            ['success' => true] + $this->detectInternalTransfers->execute($user, $limitDays, $dryRun),
        );
    }
}
