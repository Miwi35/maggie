<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\UseCase\PreviewRecurringOperation;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * What a series would recognise while it is still being written. A POST
 * because the criteria are a body, not because anything is stored: nothing is.
 */
final class PreviewRecurringOperationController
{
    public function __construct(
        private readonly Security $security,
        private readonly PreviewRecurringOperation $previewRecurringOperation,
    ) {
    }

    #[Route('/api/finance/recurring-operations/preview', name: 'api_finance_recurring_operation_preview', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $content = $request->getContent();
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body is not valid JSON.'], Response::HTTP_BAD_REQUEST);
        }

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object.'], Response::HTTP_BAD_REQUEST);
        }

        $types = [
            'counterpartyName' => 'is_string',
            'labelPattern' => 'is_string',
            'referenceAmountCents' => 'is_int',
            'amountTolerancePercent' => 'is_int',
            'accountId' => 'is_string',
        ];
        foreach ($types as $field => $check) {
            if (isset($body[$field]) && !$check($body[$field])) {
                return new JsonResponse(['error' => "{$field} has the wrong type."], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            $preview = $this->previewRecurringOperation->execute(
                user: $user,
                referenceAmountCents: $body['referenceAmountCents'] ?? 0,
                counterpartyName: $body['counterpartyName'] ?? null,
                labelPattern: $body['labelPattern'] ?? null,
                amountTolerancePercent: $body['amountTolerancePercent'] ?? RecurringOperation::DEFAULT_AMOUNT_TOLERANCE_PERCENT,
                accountId: $body['accountId'] ?? null,
            );
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($preview);
    }
}
