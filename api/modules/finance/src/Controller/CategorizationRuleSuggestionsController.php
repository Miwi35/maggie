<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Maggie\Finance\UseCase\SuggestCategorizationRules;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The rules the statement implies, and the ones the user keeps.
 *
 * Reading and accepting are two requests on purpose: a suggestion is an offer,
 * and nothing is written until someone says yes to it.
 */
final class CategorizationRuleSuggestionsController
{
    public function __construct(
        private readonly Security $security,
        private readonly SuggestCategorizationRules $suggestCategorizationRules,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
    ) {
    }

    #[Route(
        '/api/finance/categorization-rules/suggestions',
        name: 'api_finance_rule_suggestions',
        methods: ['GET'],
    )]
    public function suggestions(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $min = max(1, (int) $request->query->get('minOccurrences', 2));

        return new JsonResponse([
            'suggestions' => $this->suggestCategorizationRules->suggest($user, $min),
        ]);
    }

    /**
     * Creates the accepted rules and files the history under them straight
     * away — a rule the user cannot see working is a rule they do not trust.
     */
    #[Route(
        '/api/finance/categorization-rules/suggestions',
        name: 'api_finance_rule_suggestions_accept',
        methods: ['POST'],
    )]
    public function accept(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        $accepted = $body['rules'] ?? null;

        if (!\is_array($accepted) || [] === $accepted) {
            return new JsonResponse(['error' => 'rules is required'], Response::HTTP_BAD_REQUEST);
        }

        $result = $this->suggestCategorizationRules->accept($user, array_values($accepted));

        if (0 === $result['created']) {
            return new JsonResponse(['error' => 'No usable rule in the request.'], Response::HTTP_BAD_REQUEST);
        }

        $applied = $this->applyCategorizationRules->execute($user);

        return new JsonResponse(['success' => true, 'categorized' => $applied['categorized']] + $result);
    }
}
