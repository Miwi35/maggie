<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ApplyEnvelopeAdjustments;
use Maggie\Finance\UseCase\GetEnvelopeAdjustments;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * Adjusting the envelopes to what was really spent: GET reads each monthly
 * envelope of a month against its actuals with the suggestions for the
 * envelopes that do not fit; POST sets next month's amounts. Nothing is
 * written but by the POST.
 */
final class EnvelopeAdjustmentsController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetEnvelopeAdjustments $getEnvelopeAdjustments,
        private readonly ApplyEnvelopeAdjustments $applyEnvelopeAdjustments,
    ) {
    }

    #[Route('/api/finance/envelope-adjustments', name: 'api_finance_envelope_adjustments', methods: ['GET', 'POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        if ($request->isMethod('POST')) {
            return $this->apply($request, $user);
        }

        $month = self::month($request->query->get('month', (new \DateTimeImmutable('today'))->format('Y-m')));
        if (null === $month) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getEnvelopeAdjustments->execute($user, $month));
    }

    private function apply(Request $request, User $user): JsonResponse
    {
        try {
            $body = '' === $request->getContent() ? [] : json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body must be valid JSON'], Response::HTTP_BAD_REQUEST);
        }
        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $month = self::month($body['month'] ?? null);
        if (null === $month) {
            return new JsonResponse(['error' => 'month is required, as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        $adjustments = $body['adjustments'] ?? null;
        if (!\is_array($adjustments) || !array_is_list($adjustments) || [] === $adjustments) {
            return new JsonResponse(['error' => 'adjustments is required: a list of {categoryId, amountCents, seasonal?}'], Response::HTTP_BAD_REQUEST);
        }

        $clean = [];
        foreach ($adjustments as $adjustment) {
            $categoryId = \is_array($adjustment) ? ($adjustment['categoryId'] ?? null) : null;
            $amountCents = \is_array($adjustment) ? ($adjustment['amountCents'] ?? null) : null;
            $seasonal = \is_array($adjustment) ? ($adjustment['seasonal'] ?? false) : false;

            if (!\is_string($categoryId) || !Ulid::isValid($categoryId)) {
                return new JsonResponse(['error' => 'Each adjustment needs a categoryId'], Response::HTTP_BAD_REQUEST);
            }
            if (!\is_int($amountCents) || $amountCents < 0) {
                return new JsonResponse(['error' => 'Each adjustment needs amountCents, a positive integer'], Response::HTTP_BAD_REQUEST);
            }
            if (!\is_bool($seasonal)) {
                return new JsonResponse(['error' => 'seasonal must be a boolean'], Response::HTTP_BAD_REQUEST);
            }

            $clean[] = ['categoryId' => $categoryId, 'amountCents' => $amountCents, 'seasonal' => $seasonal];
        }

        try {
            $applied = $this->applyEnvelopeAdjustments->execute($user, $month, $clean);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true, 'applied' => $applied]);
    }

    private static function month(mixed $value): ?\DateTimeImmutable
    {
        if (!\is_string($value) || 1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $value)) {
            return null;
        }

        return new \DateTimeImmutable($value.'-01');
    }
}
