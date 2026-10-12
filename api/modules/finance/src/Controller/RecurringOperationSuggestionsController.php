<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\SuggestRecurringOperations;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The recurring operations the statement implies.
 *
 * Reading and accepting are two requests on purpose: a suggestion is an
 * offer, and no series exists — none enters the projections — until the
 * owner says yes to it.
 */
final class RecurringOperationSuggestionsController
{
    public function __construct(
        private readonly Security $security,
        private readonly SuggestRecurringOperations $suggestRecurringOperations,
    ) {
    }

    #[Route(
        '/api/finance/recurring-operations/suggestions',
        name: 'api_finance_recurring_operation_suggestions',
        methods: ['GET'],
    )]
    public function suggestions(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $min = SuggestRecurringOperations::DEFAULT_MIN_OCCURRENCES;
        if ($request->query->has('minOccurrences')) {
            $min = filter_var($request->query->get('minOccurrences'), \FILTER_VALIDATE_INT);
            if (false === $min || $min < 2) {
                return new JsonResponse(['error' => 'minOccurrences must be an integer of at least 2'], Response::HTTP_BAD_REQUEST);
            }
        }

        return new JsonResponse([
            'suggestions' => $this->suggestRecurringOperations->suggest($user, $min),
        ]);
    }

    /**
     * Creates the accepted series and attaches the history they recognise
     * straight away: a series born without its past would show no next due
     * date and no cost.
     */
    #[Route(
        '/api/finance/recurring-operations/suggestions/accept',
        name: 'api_finance_recurring_operation_suggestions_accept',
        methods: ['POST'],
    )]
    public function accept(Request $request): JsonResponse
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

        $accepted = \is_array($body) ? ($body['suggestions'] ?? null) : null;
        if (!\is_array($accepted) || [] === $accepted) {
            return new JsonResponse(['error' => 'suggestions is required'], Response::HTTP_BAD_REQUEST);
        }

        $result = $this->suggestRecurringOperations->accept($user, array_values(array_filter($accepted, \is_array(...))));

        if (0 === $result['created']) {
            return new JsonResponse(['error' => 'No usable suggestion in the request.'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true] + $result);
    }
}
