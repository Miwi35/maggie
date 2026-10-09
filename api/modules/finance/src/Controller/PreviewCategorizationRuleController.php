<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\PreviewCategorizationRule;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * What a rule would catch while it is still being written. A POST because the
 * criteria are a body, not because anything is stored: nothing is.
 */
final class PreviewCategorizationRuleController
{
    public function __construct(
        private readonly Security $security,
        private readonly PreviewCategorizationRule $previewCategorizationRule,
    ) {
    }

    #[Route('/api/finance/categorization-rules/preview', name: 'api_finance_rule_preview', methods: ['POST'])]
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
            'labelPattern' => 'is_string',
            'matchType' => 'is_string',
            'direction' => 'is_string',
            'minAmountCents' => 'is_int',
            'maxAmountCents' => 'is_int',
            'categoryId' => 'is_string',
            'priority' => 'is_int',
            'isActive' => 'is_bool',
            'ruleId' => 'is_string',
        ];
        foreach ($types as $field => $check) {
            if (isset($body[$field]) && !$check($body[$field])) {
                return new JsonResponse(['error' => "{$field} has the wrong type."], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            $preview = $this->previewCategorizationRule->execute(
                user: $user,
                labelPattern: $body['labelPattern'] ?? '',
                matchType: $body['matchType'] ?? 'contains',
                direction: $body['direction'] ?? 'any',
                minAmountCents: $body['minAmountCents'] ?? null,
                maxAmountCents: $body['maxAmountCents'] ?? null,
                categoryId: $body['categoryId'] ?? null,
                priority: $body['priority'] ?? 0,
                isActive: $body['isActive'] ?? true,
                ruleId: $body['ruleId'] ?? null,
            );
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($preview);
    }
}
