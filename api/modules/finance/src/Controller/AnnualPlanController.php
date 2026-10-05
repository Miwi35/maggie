<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ApplyAnnualPlan;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\PlanningYear;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
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
        $threshold = $request->query->get('thresholdCents');

        foreach (['year' => $year, 'thresholdCents' => $threshold] as $name => $value) {
            // `(int) 'abc'` is 0, which would silently turn every categorized
            // debit of the year into a big expense.
            if (null !== $value && 1 !== preg_match('/^-?\d+$/', (string) $value)) {
                return new JsonResponse(['error' => "{$name} must be an integer"], Response::HTTP_BAD_REQUEST);
            }
        }

        try {
            return new JsonResponse($this->getAnnualPlan->execute(
                $user,
                null === $year ? PlanningYear::default(new \DateTimeImmutable()) : (int) $year,
                null === $threshold ? GetAnnualPlan::DEFAULT_THRESHOLD_CENTS : (int) $threshold,
            ));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
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

        if (!\is_int($body['year'] ?? null)) {
            return new JsonResponse(['error' => 'year is a required integer'], Response::HTTP_BAD_REQUEST);
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
                $body['year'],
                array_values($body['events'] ?? []),
                array_values($body['envelopes'] ?? []),
                \is_string($accountId) ? $accountId : null,
            ));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        } catch (HandlerFailedException $e) {
            // Nothing validates a command on the bus, so a handler refusing
            // one is still bad input — and the MCP tool already answers it as
            // such. A 500 here would make the two channels disagree.
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
