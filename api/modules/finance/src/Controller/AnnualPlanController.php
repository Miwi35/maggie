<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ApplyAnnualPlan;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The yearly planning session: read what to decide, then write the decision. */
final class AnnualPlanController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetAnnualPlan $getAnnualPlan,
        private readonly ApplyAnnualPlan $applyAnnualPlan,
    ) {
    }

    #[Route('/api/finance/annual-plan', name: 'api_finance_annual_plan', methods: ['GET'])]
    public function review(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $year = $request->query->get('year');
        $year = null === $year ? GetAnnualPlan::defaultYear(new \DateTimeImmutable()) : (int) $year;

        if ($year < 2000 || $year > 2100) {
            return new JsonResponse(['error' => 'year must be between 2000 and 2100'], Response::HTTP_BAD_REQUEST);
        }

        $threshold = $request->query->get('thresholdCents');
        $threshold = null === $threshold ? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS : (int) $threshold;

        if ($threshold < 0) {
            return new JsonResponse(
                ['error' => 'thresholdCents must be a positive integer of cents'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        return new JsonResponse($this->getAnnualPlan->execute($user, $year, $threshold));
    }

    #[Route('/api/finance/annual-plan', name: 'api_finance_annual_plan_apply', methods: ['POST'])]
    public function apply(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();

        try {
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $year = $body['year'] ?? null;
        if (!\is_int($year) || $year < 2000 || $year > 2100) {
            return new JsonResponse(
                ['error' => 'year is a required integer between 2000 and 2100'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        foreach (['events', 'envelopes'] as $key) {
            if (!\is_array($body[$key] ?? [])) {
                return new JsonResponse(['error' => "{$key} must be a list"], Response::HTTP_BAD_REQUEST);
            }
        }

        $accountId = $body['accountId'] ?? null;

        try {
            return new JsonResponse(['success' => true] + $this->applyAnnualPlan->execute(
                $user,
                $year,
                array_values($body['events'] ?? []),
                array_values($body['envelopes'] ?? []),
                \is_string($accountId) ? $accountId : null,
            ));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
