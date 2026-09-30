<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\RollOverEnvelopes;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class RollOverEnvelopesController
{
    public function __construct(
        private readonly Security $security,
        private readonly RollOverEnvelopes $rollOverEnvelopes,
    ) {
    }

    #[Route('/api/finance/rollover-envelopes', name: 'api_finance_rollover_envelopes', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);

        $fromYear = $body['fromYear'] ?? null;
        $year = $body['year'] ?? null;

        if (!\is_int($fromYear) || !\is_int($year)) {
            return new JsonResponse(
                ['error' => 'fromYear and year are required integers'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $fromMonth = $body['fromMonth'] ?? null;
        $month = $body['month'] ?? null;

        foreach (['fromMonth' => $fromMonth, 'month' => $month] as $name => $value) {
            if (null !== $value && (!\is_int($value) || $value < 1 || $value > 12)) {
                return new JsonResponse(
                    ['error' => "{$name} must be an integer between 1 and 12"],
                    Response::HTTP_BAD_REQUEST,
                );
            }
        }

        return new JsonResponse(['success' => true] + $this->rollOverEnvelopes->execute(
            $user,
            $fromYear,
            $fromMonth,
            $year,
            $month,
            (bool) ($body['useActualSpending'] ?? false),
        ));
    }
}
